-- Run whole in the Supabase SQL Editor; idempotent.
-- Security: tables have RLS and no policies (no direct access with the publishable key);
-- all access goes through yb_* security definer functions that check the account secret (stored as SHA-256 only).

create table if not exists public.ping (id int primary key default 1, ok boolean not null default true);
insert into public.ping (id, ok) values (1, true) on conflict (id) do nothing;
alter table public.ping enable row level security;
drop policy if exists "ping read" on public.ping;
create policy "ping read" on public.ping for select to anon, authenticated using (true);
grant select on public.ping to anon, authenticated;

create table if not exists public.yb_accounts (
  id          uuid primary key,
  secret_hash text not null,
  created_at  timestamptz not null default now(),
  deleted_at  timestamptz            -- not null = deleted (tombstone: backups stop working)
);
create table if not exists public.yb_sessions (
  id           text primary key,
  account_id   uuid not null references public.yb_accounts(id) on delete cascade,
  label        text not null default 'Устройство',
  created_at   timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  revoked_at   timestamptz
);
create index if not exists yb_sessions_account_idx on public.yb_sessions(account_id);

alter table public.yb_accounts enable row level security;
alter table public.yb_sessions enable row level security;
revoke all on public.yb_accounts, public.yb_sessions from anon, authenticated;

create or replace function public.yb_hash(p text) returns text
language sql immutable as $$ select encode(sha256(convert_to(coalesce(p,''), 'UTF8')), 'hex') $$;

create or replace function public.yb_auth(p_account uuid, p_secret text) returns text
language plpgsql security definer set search_path = public as $$
declare a public.yb_accounts;
begin
  select * into a from public.yb_accounts where id = p_account;
  if not found then return 'unknown'; end if;
  if a.deleted_at is not null then return 'deleted'; end if;
  if a.secret_hash <> public.yb_hash(p_secret) then return 'denied'; end if;
  return 'ok';
end $$;

create or replace function public.yb_session_list(p_account uuid) returns jsonb
language sql security definer set search_path = public as $$
  select coalesce(jsonb_agg(jsonb_build_object(
           'id', id, 'label', label,
           'created_at', (extract(epoch from created_at) * 1000)::bigint,
           'last_seen_at', (extract(epoch from last_seen_at) * 1000)::bigint
         ) order by last_seen_at desc), '[]'::jsonb)
  from public.yb_sessions where account_id = p_account and revoked_at is null
$$;

-- Registers the account on first call. status: ok | revoked | denied | deleted | bad_request
create or replace function public.yb_sync(p_account uuid, p_secret text, p_session text, p_label text default 'Устройство')
returns jsonb language plpgsql security definer set search_path = public as $$
declare st text; s public.yb_sessions;
begin
  if p_account is null or coalesce(length(p_secret), 0) < 16 or coalesce(length(p_session), 0) < 6 then
    return jsonb_build_object('status', 'bad_request');
  end if;
  st := public.yb_auth(p_account, p_secret);
  if st = 'unknown' then
    insert into public.yb_accounts(id, secret_hash) values (p_account, public.yb_hash(p_secret))
      on conflict (id) do nothing;
    st := public.yb_auth(p_account, p_secret);
  end if;
  if st <> 'ok' then return jsonb_build_object('status', st); end if;

  select * into s from public.yb_sessions where id = p_session;
  if found and s.account_id = p_account and s.revoked_at is not null then
    return jsonb_build_object('status', 'revoked');
  end if;

  insert into public.yb_sessions(id, account_id, label)
    values (p_session, p_account, left(coalesce(nullif(p_label, ''), 'Устройство'), 80))
  on conflict (id) do update
    set account_id = excluded.account_id, label = excluded.label, last_seen_at = now(), revoked_at = null;

  return jsonb_build_object('status', 'ok', 'sessions', public.yb_session_list(p_account));
end $$;

-- Checks an account without registering it (backup import). status: ok | unknown | denied | deleted
create or replace function public.yb_check(p_account uuid, p_secret text) returns jsonb
language sql security definer set search_path = public as $$
  select jsonb_build_object('status', public.yb_auth(p_account, p_secret))
$$;

create or replace function public.yb_revoke(p_account uuid, p_secret text, p_session text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare st text;
begin
  st := public.yb_auth(p_account, p_secret);
  if st <> 'ok' then return jsonb_build_object('status', st); end if;
  update public.yb_sessions set revoked_at = now() where id = p_session and account_id = p_account;
  return jsonb_build_object('status', 'ok', 'sessions', public.yb_session_list(p_account));
end $$;

-- Revokes all other sessions and rotates the secret: old backups and other devices stop working.
create or replace function public.yb_rotate(p_account uuid, p_secret text, p_new_secret text, p_keep_session text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare st text;
begin
  if coalesce(length(p_new_secret), 0) < 16 then return jsonb_build_object('status', 'bad_request'); end if;
  st := public.yb_auth(p_account, p_secret);
  if st <> 'ok' then return jsonb_build_object('status', st); end if;
  update public.yb_accounts set secret_hash = public.yb_hash(p_new_secret) where id = p_account;
  update public.yb_sessions set revoked_at = now()
    where account_id = p_account and id <> p_keep_session and revoked_at is null;
  return jsonb_build_object('status', 'ok', 'sessions', public.yb_session_list(p_account));
end $$;

-- Deletes sessions but keeps the account as a tombstone, so any backup of it is rejected.
create or replace function public.yb_delete_account(p_account uuid, p_secret text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare st text;
begin
  st := public.yb_auth(p_account, p_secret);
  if st = 'deleted' then return jsonb_build_object('status', 'deleted'); end if;
  if st = 'unknown' then
    insert into public.yb_accounts(id, secret_hash, deleted_at) values (p_account, '!', now()) on conflict (id) do nothing;
    return jsonb_build_object('status', 'deleted');
  end if;
  if st <> 'ok' then return jsonb_build_object('status', st); end if;
  delete from public.yb_sessions where account_id = p_account;
  update public.yb_accounts set deleted_at = now(), secret_hash = '!' where id = p_account;
  return jsonb_build_object('status', 'deleted');
end $$;

-- Internal functions are locked down; only the public ones are executable by anon.
revoke all on function public.yb_hash(text), public.yb_auth(uuid, text), public.yb_session_list(uuid) from public, anon, authenticated;
revoke all on function public.yb_sync(uuid, text, text, text), public.yb_check(uuid, text), public.yb_revoke(uuid, text, text),
  public.yb_rotate(uuid, text, text, text), public.yb_delete_account(uuid, text) from public;
grant execute on function public.yb_sync(uuid, text, text, text), public.yb_check(uuid, text), public.yb_revoke(uuid, text, text),
  public.yb_rotate(uuid, text, text, text), public.yb_delete_account(uuid, text) to anon, authenticated;

notify pgrst, 'reload schema';
