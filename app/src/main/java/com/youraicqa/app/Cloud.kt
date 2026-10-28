package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

object Cloud {
    val URL_BASE: String get() = Transport.cloudUrl("sessions", "")
    private val API_KEY: String get() = Transport.cloudKey("sessions")

    private const val PREFS = "nanoai"
    private const val LOCAL = "nanoai_cloud"
    const val K_ID = "cloud_account_id"
    const val K_SECRET = "cloud_account_secret"
    private const val K_NEXT = "cloud_account_secret_next"
    private const val L_CACHE = "sessions_cache"
    private const val L_LAST = "last_sync"
    private const val L_BLOCK = "block"

    data class CloudSession(val id: String, val label: String, val createdAt: Long, val lastSeenAt: Long)
    data class Result(val status: String, val sessions: List<CloudSession>? = null) {
        val ok get() = status == "ok"
        val offline get() = status == "offline"
    }

    private val main = Handler(Looper.getMainLooper())
    /** 0 - last request succeeded, -1 - no connection, otherwise the HTTP status of the failed request */
    @Volatile var lastHttpError = 0
    @Volatile private var lastSyncAttempt = 0L
    @Volatile private var syncing = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun local(c: Context) = c.getSharedPreferences(LOCAL, Context.MODE_PRIVATE)

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes); SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }

    @Synchronized fun accountId(c: Context): String {
        val p = prefs(c)
        p.getString(K_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        p.edit().putString(K_ID, id).commit()
        return id
    }

    @Synchronized private fun accountSecret(c: Context): String {
        val p = prefs(c)
        p.getString(K_SECRET, null)?.takeIf { it.length >= 16 }?.let { return it }
        val s = randomToken(32)
        p.edit().putString(K_SECRET, s).commit()
        return s
    }

    private fun rpc(fn: String, body: JSONObject): JSONObject? {
        val base = URL_BASE
        // The cloud address is not baked into the APK: it arrives from the transport and is cached in transport.json
        if (base.isBlank() || API_KEY.isBlank()) { lastHttpError = -1; return null }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/rest/v1/rpc/$fn").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000; readTimeout = 15_000
                doOutput = true
                setRequestProperty("apikey", API_KEY)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) { lastHttpError = code; return null }
            lastHttpError = 0
            JSONObject(text)
        } catch (_: Exception) {
            lastHttpError = -1
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parse(o: JSONObject?): Result {
        if (o == null) return Result("offline")
        val arr = o.optJSONArray("sessions")
        val list = arr?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                val id = s.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CloudSession(id, s.optString("label", "Устройство"), s.optLong("created_at"), s.optLong("last_seen_at"))
            }
        }
        return Result(o.optString("status", "offline"), list)
    }

    // A denied secret is retried with the pending rotated one (set when other sessions are signed out)
    fun syncBlocking(c: Context): Result {
        val app = c.applicationContext
        val id = accountId(app)
        val sid = SessionsManager.mySessionId(app)
        fun call(secret: String) = parse(rpc("yb_sync", JSONObject()
            .put("p_account", id).put("p_secret", secret).put("p_session", sid).put("p_label", SessionsManager.myLabel(app))))
        var r = call(accountSecret(app))
        if (r.status == "denied") {
            val next = prefs(app).getString(K_NEXT, null)
            if (!next.isNullOrBlank()) {
                val r2 = call(next)
                if (r2.ok) prefs(app).edit().putString(K_SECRET, next).remove(K_NEXT).commit()
                r = r2
            }
        }
        lastSyncAttempt = System.currentTimeMillis()
        when (r.status) {
            "ok" -> {
                local(app).edit().remove(L_BLOCK).putLong(L_LAST, System.currentTimeMillis())
                    .putString(L_CACHE, sessionsToJson(r.sessions.orEmpty()).toString()).apply()
            }
            "revoked", "denied", "deleted" -> local(app).edit().putString(L_BLOCK, r.status).apply()
        }
        return r
    }

    // At most one attempt per minute unless forced
    fun syncInBackground(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        if (syncing) return
        if (!force && System.currentTimeMillis() - lastSyncAttempt < 60_000L) return
        syncing = true
        Thread {
            try {
                val r = syncBlocking(app)
                if (blocked(app) != null) main.post { NanoApp.currentActivity()?.let { showBlockedDialog(it) } }
            } catch (_: Exception) {
            } finally { syncing = false }
        }.start()
    }

    fun cachedSessions(c: Context): List<CloudSession> = try {
        val a = JSONArray(local(c).getString(L_CACHE, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val s = a.optJSONObject(i) ?: return@mapNotNull null
            CloudSession(s.optString("id"), s.optString("label", "Устройство"), s.optLong("createdAt"), s.optLong("lastSeenAt"))
        }
    } catch (_: Exception) { emptyList() }

    fun lastSyncAt(c: Context): Long = local(c).getLong(L_LAST, 0L)

    private fun sessionsToJson(l: List<CloudSession>) = JSONArray().also { a ->
        l.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label).put("createdAt", it.createdAt).put("lastSeenAt", it.lastSeenAt)) }
    }

    fun revokeSession(c: Context, sessionId: String): Result {
        val r = parse(rpc("yb_revoke", JSONObject()
            .put("p_account", accountId(c)).put("p_secret", accountSecret(c)).put("p_session", sessionId)))
        if (r.ok) local(c).edit().putString(L_CACHE, sessionsToJson(r.sessions.orEmpty()).toString()).apply()
        return r
    }

    fun signOutOthers(c: Context): Result {
        val app = c.applicationContext
        val next = randomToken(32)
        prefs(app).edit().putString(K_NEXT, next).commit()
        val r = parse(rpc("yb_rotate", JSONObject()
            .put("p_account", accountId(app)).put("p_secret", accountSecret(app)).put("p_new_secret", next)
            .put("p_keep_session", SessionsManager.mySessionId(app))))
        if (r.ok) {
            prefs(app).edit().putString(K_SECRET, next).remove(K_NEXT).commit()
            local(app).edit().putString(L_CACHE, sessionsToJson(r.sessions.orEmpty()).toString()).apply()
        } else {
            prefs(app).edit().remove(K_NEXT).commit()
        }
        return r
    }

    fun deleteAccountRemote(c: Context): Result {
        val app = c.applicationContext
        fun call(secret: String) = rpc("yb_delete_account", JSONObject().put("p_account", accountId(app)).put("p_secret", secret))
        var o = call(accountSecret(app))
        // The secret may have been rotated (other sessions were signed out): retry with the pending one
        if (o != null && o.optString("status") == "denied") {
            val next = prefs(app).getString(K_NEXT, null)
            if (!next.isNullOrBlank()) o = call(next) ?: o
        }
        if (o == null) return Result(if (lastHttpError > 0) "http_$lastHttpError" else "offline")
        return parse(o)
    }

    fun checkBackup(backup: JSONObject): Result {
        val nano = backup.optJSONObject("prefsFiles")?.optJSONObject("nanoai") ?: return Result("unknown")
        val id = nano.optJSONObject(K_ID)?.optString("v").orEmpty()
        val secret = nano.optJSONObject(K_SECRET)?.optString("v").orEmpty()
        if (id.isBlank() || secret.isBlank()) return Result("unknown")
        return parse(rpc("yb_check", JSONObject().put("p_account", id).put("p_secret", secret)))
    }

    fun blocked(c: Context): String? = local(c).getString(L_BLOCK, null)

    @Volatile var wiping = false

    fun wipeLocalAccount(c: Context) {
        val app = c.applicationContext
        wiping = true
        BackupManager.wipeLocalAccount(app)
        local(app).edit().clear().commit()
    }

    /**
     * Restarts the app in a fresh process. The launch is done by [RestartActivity] living in a separate process,
     * so that killing this process cannot take the new Splash screen down with it.
     */
    fun restartApp(c: Context) {
        val app = c.applicationContext
        val next = Intent(app, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val trampoline = Intent(app, RestartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(RestartActivity.EXTRA_NEXT, next)
        try { app.startActivity(trampoline) } catch (_: Exception) {
            wiping = false
            BackupManager.clearPendingWipe(app)
            try { app.startActivity(next) } catch (_: Exception) {}
            (c as? Activity)?.finishAffinity()
            return
        }
        (c as? Activity)?.finishAffinity()
        // Give the trampoline time to start in its own process, then end this one
        main.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
    }

    private var blockedDialogFor: java.lang.ref.WeakReference<Activity>? = null

    fun showBlockedDialog(activity: Activity) {
        val reason = blocked(activity) ?: return
        if (blockedDialogFor?.get() === activity || activity.isFinishing) return
        val (title, msg) = when (reason) {
            "deleted" -> "Аккаунт удалён" to "Этот аккаунт был удалён. Данные на этом устройстве будут стёрты."
            "denied" -> "Сессия завершена" to "Владелец аккаунта завершил все сессии (или сменил доступ). Данные аккаунта на этом устройстве будут стёрты."
            else -> "Устройство отключено" to "Владелец аккаунта отключил это устройство. Данные аккаунта на нём будут стёрты."
        }
        blockedDialogFor = java.lang.ref.WeakReference(activity)
        val onOk = {
            Thread {
                wipeLocalAccount(activity)
                main.post { restartApp(activity) }
            }.start()
        }
        if (activity is NanoBaseActivity) {
            activity.styledConfirmDialog(title, msg, "Понятно", destructive = true, mandatory = true) { onOk() }
        } else {
            android.app.AlertDialog.Builder(activity).setTitle(title).setMessage(msg).setCancelable(false)
                .setPositiveButton("Понятно") { _, _ -> onOk() }.show()
        }
    }
}
