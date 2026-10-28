package com.youraicqa.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Address transport. The ONLY address baked into the app is TRANSPORT_URL.
 *
 * - While the app is active it asks the transport once an hour (and once on every app start).
 * - A changed document is converted to a local transport.json that is kept forever and takes priority.
 * - If the transport is unreachable (network, 5xx, 429), the saved json (or the built-in defaults) is used.
 * - The json holds ADDRESSES only: controllers (id -> url) and cloud sessions (id -> url).
 */
object Transport {
    const val TRANSPORT_URL = "https://voncure-studio--4f254b18d27b11f1b4d31607ee4eb77e.web.val.run"
    const val FILE_NAME = "transport.json"

    // Safety: addresses from the transport are accepted only for these hosts (or the built-in default's host),
    // so a hijacked transport cannot send account secrets to a foreign server. Add your hosts here if needed.
    private val CONTROLLER_HOSTS = listOf("val.run")
    private val CLOUD_HOSTS = listOf("supabase.co", "supabase.in")

    private const val DEFAULT_POLL_MS = 60L * 60 * 1000
    private const val RETRY_MS = 10L * 60 * 1000
    private const val MAX_BODY = 64 * 1024
    private val KEY_RE = Regex("[A-Za-z0-9._-]{10,400}")

    @Volatile private var file: File? = null
    @Volatile private var rev = -1L
    @Volatile private var pollMs = DEFAULT_POLL_MS
    @Volatile private var controllers: Map<String, String> = emptyMap()
    @Volatile private var cloud: Map<String, String> = emptyMap()
    @Volatile private var cloudKeys: Map<String, String> = emptyMap()
    @Volatile private var nextAttemptAt = 0L
    @Volatile private var busy = false
    @Volatile var lastStatus = "not synced yet"
        private set

    /** Loads the saved json (if any). Safe to call many times. */
    @Synchronized fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        try {
            if (f.isFile) applyLocal(f.readText(Charsets.UTF_8))
        } catch (_: Exception) {
        }
    }

    /** Controller address by id, or [fallback] when the transport gave nothing usable. */
    fun controllerUrl(id: String, fallback: String): String = pick(controllers, id, fallback, CONTROLLER_HOSTS)

    /** Cloud-sessions address by id, or [fallback]. */
    fun cloudUrl(id: String, fallback: String): String = pick(cloud, id, fallback, CLOUD_HOSTS)

    /** Publishable (anon) key of the cloud with this id; empty if the transport gave none. */
    fun cloudKey(id: String): String = cloudKeys[id].orEmpty()

    private fun pick(map: Map<String, String>, id: String, fallback: String, suffixes: List<String>): String {
        val u = map[id] ?: return fallback
        val host = hostOf(u) ?: return fallback
        val fbHost = hostOf(fallback)
        val ok = host == fbHost || suffixes.any { host == it || host.endsWith(".$it") }
        return if (ok) u else fallback
    }

    private fun hostOf(u: String): String? = try {
        val x = URL(u)
        if (x.protocol == "https" && x.userInfo == null && x.query == null && x.ref == null) x.host.lowercase() else null
    } catch (_: Exception) {
        null
    }

    /** Called from the app's polling; real network work happens at most once per poll interval. */
    fun refreshInBackground(context: Context, force: Boolean = false) {
        if (TRANSPORT_URL.isBlank() || busy) return
        val now = System.currentTimeMillis()
        if (!force && now < nextAttemptAt) return
        busy = true
        nextAttemptAt = now + RETRY_MS // overwritten with the poll interval on success
        val app = context.applicationContext
        Thread {
            try {
                fetchOnce(app)
            } catch (e: Exception) {
                lastStatus = "transport unreachable: ${e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }.start()
    }

    /** Blocking variant for a manual "sync now". */
    fun syncNow(context: Context): String {
        try {
            nextAttemptAt = System.currentTimeMillis() + RETRY_MS
            fetchOnce(context.applicationContext)
        } catch (e: Exception) {
            lastStatus = "transport unreachable: ${e.javaClass.simpleName}"
        }
        return lastStatus
    }

    @Synchronized private fun fetchOnce(app: Context) {
        init(app)
        var conn: HttpURLConnection? = null
        try {
            val q = if (rev >= 0) "?rev=$rev" else ""
            conn = (URL(TRANSPORT_URL.trimEnd('/') + "/transport" + q).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code == 429) {
                val sec = conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 1800L
                nextAttemptAt = System.currentTimeMillis() + sec.coerceIn(60L, 3600L) * 1000
                lastStatus = "transport: rate limited, retry in ${sec}s (saved json in use)"
                return
            }
            if (code !in 200..299) { lastStatus = "transport: HTTP $code (saved json in use)"; return }
            val text = readLimited(conn) ?: run { lastStatus = "transport: response too large"; return }
            val doc = JSONObject(text)
            val poll = doc.optLong("pollMs", pollMs).coerceIn(5L * 60 * 1000, 24L * 3600 * 1000)
            if (doc.optBoolean("unchanged", false)) {
                pollMs = poll
                nextAttemptAt = System.currentTimeMillis() + poll
                lastStatus = "transport: no changes (rev $rev)"
                return
            }
            val local = convert(doc, poll) ?: run { lastStatus = "transport: invalid document"; return }
            save(local.toString())
            applyLocal(local.toString())
            nextAttemptAt = System.currentTimeMillis() + poll
            lastStatus = "transport: updated to rev $rev"
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun readLimited(conn: HttpURLConnection): String? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        conn.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BODY) return null
            }
        }
        return out.toString("UTF-8")
    }

    /** Server document -> local json: {rev, savedAt, pollMs, controllers:{id:url}, cloud:{id:url}} */
    private fun convert(doc: JSONObject, poll: Long): JSONObject? {
        val newRev = doc.optLong("rev", -1L)
        if (newRev < 0) return null
        val ctrl = JSONObject()
        val items = ArrayList<JSONObject>()
        doc.optJSONArray("controllers")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(it) } }
        items.sortBy { it.optInt("priority", Int.MAX_VALUE) }
        for (o in items) {
            val id = o.optString("id"); val url = o.optString("url")
            if (id.isNotBlank() && hostOf(url) != null && !ctrl.has(id)) ctrl.put(id, url.trimEnd('/'))
        }
        val cl = JSONObject()
        val ck = JSONObject()
        doc.optJSONArray("cloud")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = o.optString("id"); val url = o.optString("url")
                if (id.isNotBlank() && hostOf(url) != null && !cl.has(id)) {
                    cl.put(id, url.trimEnd('/'))
                    val k = o.optString("key").trim()
                    if (KEY_RE.matches(k)) ck.put(id, k)
                }
            }
        }
        return JSONObject().put("rev", newRev).put("savedAt", System.currentTimeMillis())
            .put("pollMs", poll).put("controllers", ctrl).put("cloud", cl).put("cloudKeys", ck)
    }

    private fun applyLocal(text: String) {
        val j = JSONObject(text)
        val r = j.optLong("rev", -1L)
        if (r < 0) return
        fun toMap(o: JSONObject?): Map<String, String> {
            val m = HashMap<String, String>()
            if (o != null) {
                val ks = o.keys()
                while (ks.hasNext()) {
                    val k = ks.next()
                    val v = o.optString(k)
                    if (v.isNotBlank()) m[k] = v
                }
            }
            return m
        }
        controllers = toMap(j.optJSONObject("controllers"))
        cloud = toMap(j.optJSONObject("cloud"))
        cloudKeys = toMap(j.optJSONObject("cloudKeys"))
        pollMs = j.optLong("pollMs", DEFAULT_POLL_MS).coerceIn(5L * 60 * 1000, 24L * 3600 * 1000)
        rev = r
    }

    /** Atomic write: the old json is replaced only after the new one is fully on disk. */
    private fun save(text: String) {
        val f = file ?: return
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.writeText(text, Charsets.UTF_8); tmp.delete() }
    }

    fun describe(): String = "$TRANSPORT_URL | rev $rev | $lastStatus"
}
