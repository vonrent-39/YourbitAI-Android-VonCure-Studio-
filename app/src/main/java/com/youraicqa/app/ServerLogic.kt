package com.youraicqa.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object ServerLogic {

    sealed class UrlResult {
        data class Ok(val url: String) : UrlResult()
        data class Error(val message: String) : UrlResult()
    }

    private val HOST_RE = Regex("""^(\[[0-9a-fA-F:.]+\]|[\p{L}\p{N}._-]+)(:\d{1,5})?$""")

    fun normalizeBaseUrl(raw: String): UrlResult {
        val t = raw.filterNot { it.isWhitespace() }
        if (t.isEmpty()) return UrlResult.Error("Укажите адрес сервера.")
        val lower = t.lowercase()
        if (lower.startsWith("http://")) {
            return UrlResult.Error("Адрес должен начинаться с https:// — незащищённое соединение (http://) не поддерживается.")
        }
        if (!lower.startsWith("https://")) {
            return UrlResult.Error("Адрес должен начинаться с https:// (например, https://example.com).")
        }
        val rest = t.substring("https://".length)
        if (rest.contains('?') || rest.contains('#')) {
            return UrlResult.Error("В адресе не должно быть параметров (? и #). Укажите только адрес сервера.")
        }
        val slash = rest.indexOf('/')
        val hostPart = if (slash < 0) rest else rest.substring(0, slash)
        var path = if (slash < 0) "" else rest.substring(slash)
        if (hostPart.isEmpty()) return UrlResult.Error("В адресе не указан сервер.")
        if (hostPart.contains('@')) return UrlResult.Error("Не указывай логин и пароль в адресе. Ключ вводится отдельным полем.")
        if (!HOST_RE.matches(hostPart)) return UrlResult.Error("Некорректное имя сервера в адресе.")
        val port = HOST_RE.find(hostPart)?.groupValues?.get(2)?.removePrefix(":")?.toIntOrNull()
        if (port != null && port !in 1..65535) return UrlResult.Error("Некорректный порт в адресе.")

        var changed = true
        while (changed) {
            changed = false
            val trimmed = path.trimEnd('/')
            if (trimmed != path) { path = trimmed; changed = true }
            for (tail in listOf("/chat/completions", "/models")) {
                if (path.lowercase().endsWith(tail)) {
                    path = path.substring(0, path.length - tail.length)
                    changed = true
                }
            }
        }
        if (path.isEmpty()) path = "/v1"
        return UrlResult.Ok("https://" + hostPart.lowercase() + path)
    }

    fun hostOf(url: String): String? {
        val t = url.trim()
        val rest = t.substringAfter("://", missingDelimiterValue = "")
        if (rest.isEmpty()) return null
        val hostPort = rest.substringBefore('/')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.lowercase().ifEmpty { null }
    }

    fun sameHost(a: String, b: String): Boolean {
        val ha = hostOf(a) ?: return false
        val hb = hostOf(b) ?: return false
        return ha == hb
    }

    data class SseResult(val text: String, val done: Boolean)

    fun parseSse(lines: Sequence<String>, onDelta: (String) -> Unit = {}, opts: ChatOpts? = null): SseResult {
        val sb = StringBuilder()
        var done = false
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (line.startsWith(":")) continue
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") { done = true; break }
            if (opts != null) captureMeta(payload, opts)
            val piece = extractDelta(payload) ?: continue
            if (piece.isNotEmpty()) {
                sb.append(piece)
                onDelta(piece)
            }
        }
        return SseResult(sb.toString(), done)
    }

    private fun captureMeta(payload: String, opts: ChatOpts) {
        try {
            val choice = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0) ?: return
            val delta = choice.optJSONObject("delta")
            val r = (delta?.opt("reasoning_content") as? String) ?: (delta?.opt("reasoning") as? String)
            if (!r.isNullOrEmpty() && opts.reasoning.length < 20000) opts.reasoning = opts.reasoning + r
            val f = choice.opt("finish_reason") as? String
            if (!f.isNullOrEmpty()) opts.finish = f
        } catch (_: Exception) { }
    }

    private fun extractDelta(payload: String): String? = try {
        val delta = JSONObject(payload).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
        delta?.opt("content") as? String
    } catch (_: Exception) { null }

    class ChatExtract(val text: String, val reasoning: String, val finish: String, val note: String, val hasChoice: Boolean)

    private fun partsText(v: Any?): String = when (v) {
        is String -> v
        is JSONArray -> buildString {
            for (i in 0 until v.length()) {
                val p = v.opt(i)
                if (p is String) append(p)
                else if (p is JSONObject && p.opt("thought") != true) (p.opt("text") as? String)?.let { append(it) }
            }
        }
        is JSONObject -> (v.opt("text") as? String) ?: partsText(v.opt("parts"))
        else -> ""
    }

    fun extractChat(json: JSONObject): ChatExtract {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        if (choice != null) {
            val msg = choice.optJSONObject("message")
            var text = partsText(msg?.opt("content"))
            if (text.isBlank()) text = partsText(choice.opt("text"))
            var reasoning = ""
            for (k in arrayOf("reasoning_content", "reasoning")) {
                val r = msg?.opt(k) as? String
                if (!r.isNullOrBlank()) { reasoning = r; break }
            }
            val refusal = (msg?.opt("refusal") as? String).orEmpty()
            val finish = (choice.opt("finish_reason") as? String).orEmpty()
            return ChatExtract(text, reasoning, finish, if (text.isBlank() && refusal.isNotBlank()) "отказ модели: " + refusal.take(160) else "", true)
        }
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
        if (cand != null) {
            val parts = cand.optJSONObject("content")?.optJSONArray("parts")
            val sb = StringBuilder()
            val th = StringBuilder()
            if (parts != null) for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val t = (p.opt("text") as? String) ?: continue
                if (p.opt("thought") == true) th.append(t) else sb.append(t)
            }
            return ChatExtract(sb.toString(), th.toString(), (cand.opt("finishReason") as? String).orEmpty(), "", true)
        }
        val block = json.optJSONObject("promptFeedback")?.opt("blockReason") as? String
        return ChatExtract("", "", "", if (block != null) "запрос заблокирован фильтром ($block)" else "", false)
    }

    fun emptyDetail(x: ChatExtract): String {
        val f = x.finish.lowercase()
        return when {
            x.note.isNotBlank() -> x.note
            f == "length" || f == "max_tokens" ->
                if (x.reasoning.isNotBlank()) "лимит токенов кончился на скрытом рассуждении модели" else "ответ обрезан по лимиту токенов"
            f == "content_filter" || f == "safety" || f == "blocklist" || f == "prohibited_content" || f == "recitation" || f == "spii" ->
                "ответ заблокирован фильтром безопасности модели ($f)"
            x.reasoning.isNotBlank() -> "модель вернула только рассуждение без ответа"
            !x.hasChoice -> "в ответе сервера нет вариантов ответа"
            f.isNotEmpty() && f != "stop" -> "причина завершения: $f"
            else -> ""
        }
    }

    fun parseChatJson(body: String, opts: ChatOpts? = null): String {
        val json = try { JSONObject(body.trim()) } catch (_: Exception) { throw ServerException(ServerErrors.badReply(body)) }
        val x = extractChat(json)
        opts?.record(x)
        if (!x.hasChoice && x.text.isBlank()) {
            val err = json.opt("error")
            val msg = when (err) {
                is JSONObject -> err.opt("message") as? String
                is String -> err
                else -> null
            }
            if (msg != null) throw ServerException(ServerErrors.fromHttp(400, msg))
            if (opts != null && opts.tolerant) return ""
            if (x.note.isNotBlank()) throw ServerException(ServerErrors.emptyReply(x.note))
            throw ServerException(ServerErrors.badReply(body))
        }
        if (x.text.isBlank()) {
            if (opts != null && opts.tolerant) return ""
            throw ServerException(ServerErrors.emptyReply(emptyDetail(x)))
        }
        return x.text
    }

    fun readChatResponse(contentType: String?, lines: Sequence<String>, opts: ChatOpts? = null, onDelta: (String) -> Unit = {}): String {
        val ct = contentType?.lowercase().orEmpty()
        val sse: Boolean
        val source: Sequence<String>
        if (ct.contains("text/event-stream")) {
            sse = true; source = lines
        } else if (ct.contains("json")) {
            sse = false; source = lines
        } else {
            val it = lines.iterator()
            var first: String? = null
            while (it.hasNext()) {
                val l = it.next()
                if (l.isNotBlank()) { first = l; break }
            }
            if (first == null) throw StreamCutException("")
            sse = !first.trimStart().startsWith("{")
            val f = first
            source = sequence { yield(f); while (it.hasNext()) yield(it.next()) }
        }
        if (sse) {
            val r = parseSse(source, onDelta, opts)
            if (!r.done) throw StreamCutException(r.text)
            return r.text
        }
        val text = parseChatJson(source.joinToString("\n"), opts)
        onDelta(text)
        return text
    }

    data class ParsedModels(val ids: List<String>, val managed: List<String>)

    fun parseModels(body: String): ParsedModels {
        val t = body.trim()
        val arr = try {
            if (t.startsWith("[")) JSONArray(t)
            else JSONObject(t).let { it.optJSONArray("data") ?: it.optJSONArray("models") ?: it.optJSONArray("result") }
        } catch (_: Exception) { null }
            ?: throw ServerException(ServerErrors.badReply(t))
        val ids = LinkedHashSet<String>()
        val managed = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val el = arr.opt(i)
            if (el is String) { el.trim().takeIf { it.isNotEmpty() }?.let { ids.add(it) }; continue }
            val o = el as? JSONObject ?: continue
            val id = ((o.opt("id") ?: o.opt("name") ?: o.opt("model")) as? String)?.trim()
            if (id.isNullOrEmpty()) continue
            ids.add(id)
            if (o.opt("managed") == true) managed.add(id)
        }
        return ParsedModels(ids.toList(), managed.toList())
    }

    fun parseModelIds(body: String): List<String> = parseModels(body).ids

    fun replacedBy(servers: List<CustomServer>): Map<String, CustomServer> {
        val out = LinkedHashMap<String, CustomServer>()
        for (s in servers) for (id in s.managedModels) out.putIfAbsent(id, s)
        return out
    }

    enum class Phase { CONNECT, RESPONSE }

    fun shouldFailover(phase: Phase, e: Throwable): Boolean = when {
        e is UnknownHostException -> true
        e is ConnectException -> true
        e is java.net.SocketTimeoutException && phase == Phase.CONNECT -> true
        else -> false
    }

    fun shouldFailoverOnStatus(code: Int): Boolean = code == 502 || code == 503 || code == 504

    fun orderAddresses(primary: String, fallback: String, remembered: String?): List<String> {
        if (fallback.isBlank() || fallback == primary) return listOf(primary)
        return if (remembered != null && remembered == fallback) listOf(fallback, primary) else listOf(primary, fallback)
    }

    class AddressMemory(
        private val ttlMs: Long = 5 * 60_000L,
        private val now: () -> Long = { System.currentTimeMillis() }
    ) {
        private val map = ConcurrentHashMap<String, Pair<String, Long>>()
        fun get(serverId: String): String? {
            val e = map[serverId] ?: return null
            if (now() > e.second) { map.remove(serverId); return null }
            return e.first
        }
        fun remember(serverId: String, url: String) { map[serverId] = url to (now() + ttlMs) }
        fun forget(serverId: String) { map.remove(serverId) }
    }

    data class ServerModelEntry(val serverId: String, val serverName: String, val modelId: String)

    fun serverModelEntries(servers: List<CustomServer>): List<ServerModelEntry> =
        servers.flatMap { s ->
            s.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                .map { ServerModelEntry(s.id, s.name.ifBlank { s.baseUrl }, it) }
        }

    fun applyRefresh(cached: List<String>, fetched: Result<List<String>>): List<String> {
        val list = fetched.getOrNull() ?: return cached
        return if (list.isEmpty()) cached else list
    }

    fun <T> assembleModels(builtin: List<T>, user: List<T>, server: List<T>): List<T> = builtin + user + server

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun normalizeFingerprint(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    fun formatFingerprint(hex: String): String =
        normalizeFingerprint(hex).uppercase().chunked(2).joinToString(":")
}

class ChatOpts(val effort: String? = null, val tolerant: Boolean = false) {
    @Volatile var finish: String = ""
    @Volatile var reasoning: String = ""
    @Volatile var note: String = ""

    fun record(x: ServerLogic.ChatExtract) {
        finish = x.finish
        reasoning = x.reasoning
        note = x.note
    }
}
