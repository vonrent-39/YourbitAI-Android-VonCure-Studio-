package com.youraicqa.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class RemoteModel(
    val name: String, val provider: String, val id: String, val badge: String, val desc: String,
    val recommended: Boolean, val accent: Int, val isImage: Boolean,
    val isVideo: Boolean = false,
    val isVision: Boolean = false,
    val multimodal: Boolean = false,
    val unverified: Boolean = false,
    val section: String = "",
    val descLoc: Map<String, String> = emptyMap(),
    val replaces: String = ""
)

data class RemoteModelsResult(
    val rev: Long, val unchanged: Boolean, val useBuiltin: Boolean, val models: List<RemoteModel>,
    val mode: String = MODE_REPLACE,
    val hide: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    val pollMs: Long = 0L,
    val needBundled: Boolean = false
) {
    companion object {
        const val MODE_REPLACE = "replace"
        const val MODE_MERGE = "merge"
    }
}

object BuiltinModels {
    const val DEFAULT_REMOTE_URL = "https://voncure-studio--1902ed00caf711f196f11607ee4eb77e.web.val.run"
    /** Models controller address: taken from the transport json, falls back to the built-in default. */
    val REMOTE_URL: String get() = Transport.controllerUrl("models", DEFAULT_REMOTE_URL)

    private const val MIN_REFRESH_GAP_MS = 5L * 1000
    private const val DEFAULT_POLL_MS = 20L * 1000
    @Volatile private var serverPollMs = DEFAULT_POLL_MS

    fun pollIntervalMs(): Long = serverPollMs.coerceIn(10_000L, 120_000L)
    private val KNOWN_PROVIDERS = setOf(
        "groq", "gemini", "openrouter", "cloudflare", "llm7", "pollinations", "mistral", "cohere",
        "openai", "qwen", "deepseek", "anthropic", "nvidia"
    )

    const val FILE_NAME = "builtin_models.json"
    private const val K_FETCHED_AT = "fetchedAt"
    private const val PREFS = "nanoai_cloud"
    private const val K_CACHE = "models_cache"
    private const val K_CACHE_AT = "models_cache_at"
    private val fileLock = Any()
    @Volatile private var storeFile: File? = null

    @Volatile private var remote: List<MainActivity.Model>? = null
    @Volatile private var remoteRev = -1L
    @Volatile private var remoteMode = ""
    @Volatile private var lastStatus = "ещё не проверялось"
    @Volatile private var lastAttempt = 0L
    @Volatile private var refreshing = false
    private val main by lazy { Handler(Looper.getMainLooper()) }

    val list: List<MainActivity.Model>
        get() {
            return remote ?: bundled
        }

    fun init(context: Context) {
        try {
            val app = context.applicationContext
            storeFile = File(app.filesDir, FILE_NAME)
            Transport.init(app)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_CACHE).remove(K_CACHE_AT).apply()
            val text = readStore() ?: return
            val res = parse(text) ?: return
            if (res.unchanged || isFallback(res)) return
            remote = effective(res)
            remoteRev = res.rev
            remoteMode = res.mode
            lastStatus = "список загружен из локального файла (rev ${res.rev})"
        } catch (_: Exception) {
        }
    }

    fun refreshInBackground(context: Context, force: Boolean = false) {
        Transport.refreshInBackground(context, force)
        if (REMOTE_URL.isBlank() || refreshing) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAttempt < MIN_REFRESH_GAP_MS) return
        lastAttempt = now
        refreshing = true
        val app = context.applicationContext
        Thread {
            try {
                fetchAndApply(app)
            } catch (_: Exception) {
            } finally {
                refreshing = false
            }
        }.start()
    }

    // rev lets the server answer "unchanged"; bh/bv let it ask for our bundled list when it knows a different one
    private fun fetchAndApply(app: Context) {
        if (storeFile == null) storeFile = File(app.filesDir, FILE_NAME)
        val q = ArrayList<String>()
        if (remoteRev >= 0 && remote != null) q.add("rev=$remoteRev")
        q.add("bh=" + bundledHashCached()); q.add("bv=" + appVersionCode(app))
        val text = httpGet(REMOTE_URL.trimEnd('/') + "/models?" + q.joinToString("&")) ?: return
        val res = parse(text)
        if (res != null && res.pollMs > 0) serverPollMs = res.pollMs
        if (res != null && res.needBundled) reportBundledIfNeeded(app)
        if (res == null) { lastStatus = "ответ сервера не разобран (откройте <адрес>/models в браузере)"; return }
        val changed: Boolean
        when {
            res.unchanged -> {
                lastStatus = "сервер: нет изменений (rev ${res.rev})"
                return
            }
            isFallback(res) -> {
                changed = remote != null
                remote = null; remoteRev = -1L; remoteMode = ""
                deleteStore()
                lastStatus = "сервер просит вшитые модели (режим обслуживания или список пуст)"
            }
            else -> {
                val newList = effective(res)
                changed = newList != remote
                val now = System.currentTimeMillis()
                writeStore(toFileJson(text, now))
                remote = newList; remoteRev = res.rev; remoteMode = res.mode
                lastStatus = "обновлено с сервера: rev ${res.rev}, режим ${res.mode}, моделей на сервере ${res.models.size}"
            }
        }
        if (changed) main.post { (NanoApp.currentActivity() as? MainActivity)?.onBuiltinModelsChanged() }
    }

    fun toFileJson(serverText: String, fetchedAt: Long): String =
        JSONObject(serverText).put(K_FETCHED_AT, fetchedAt).toString(2)

    fun fetchedAtOf(fileText: String): Long =
        try { JSONObject(fileText).optLong(K_FETCHED_AT, 0L) } catch (_: Exception) { 0L }

    private fun isFallback(res: RemoteModelsResult): Boolean =
        res.useBuiltin || (res.models.isEmpty() && (res.mode != RemoteModelsResult.MODE_MERGE || res.hide.isEmpty()))

    private fun effective(res: RemoteModelsResult): List<MainActivity.Model> {
        val fromServer = res.models.map { it.toModel() }
        if (res.mode != RemoteModelsResult.MODE_MERGE) return fromServer
        val key = { m: MainActivity.Model -> "${m.provider}/${m.id}".lowercase() }
        val repl = res.models.filter { it.replaces.isNotEmpty() }.associate { "${it.provider}/${it.id}".lowercase() to it.replaces }
        return applyOrder(mergeByKey(bundled, fromServer, res.hide, replacesOf = { repl[key(it)] }, key = key), res.order, key)
    }

    // Keys listed in `order` go first in that order; the rest keep their relative order
    fun <T> applyOrder(list: List<T>, order: List<String>, key: (T) -> String): List<T> {
        if (order.isEmpty()) return list
        val idx = HashMap<String, Int>()
        order.forEachIndexed { i, k -> idx.putIfAbsent(k, i) }
        return list.sortedBy { idx[key(it)] ?: Int.MAX_VALUE }
    }

    // Overlay entries override base entries with the same key; `replaces` swaps a base entry for another one, `hide` removes it
    fun <T> mergeByKey(
        base: List<T>, overlay: List<T>, hide: Set<String>,
        replacesOf: (T) -> String? = { null }, key: (T) -> String
    ): List<T> {
        val over = overlay.associateBy(key)
        val repl = HashMap<String, T>()
        for (o in overlay) replacesOf(o)?.takeIf { it.isNotEmpty() && it != key(o) }?.let { repl.putIfAbsent(it, o) }
        val used = HashSet<String>()
        val out = ArrayList<T>()
        for (b in base) {
            val k = key(b)
            val r = repl[k]
            if (r != null) { out.add(r); used.add(key(r)); continue }
            if (k in hide) continue
            val o = over[k]
            if (o != null) used.add(k)
            out.add(o ?: b)
        }
        for (o in overlay) if (key(o) !in used) out.add(o)
        return out.distinctBy(key)
    }

    private fun readStore(): String? = synchronized(fileLock) {
        try {
            val f = storeFile ?: return null
            if (f.isFile) f.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeStore(text: String): Boolean = synchronized(fileLock) {
        try {
            val f = storeFile ?: return false
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) { f.writeText(text, Charsets.UTF_8); tmp.delete() }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteStore() = synchronized(fileLock) {
        try { storeFile?.delete() } catch (_: Exception) {}
    }

    fun syncNow(context: Context): String {
        try {
            Transport.syncNow(context.applicationContext)
            if (REMOTE_URL.isNotBlank()) fetchAndApply(context.applicationContext)
        } catch (e: Exception) {
            lastStatus = "ошибка: ${e.javaClass.simpleName}: ${e.message}"
        }
        return describe()
    }

    fun describe(): String {
        val r = remote
        val f = storeFile
        return buildString {
            append("Сервер моделей: ").append(if (REMOTE_URL.isBlank()) "НЕ ЗАДАН (приложение собрано без адреса)" else REMOTE_URL).append('\n')
            append("Проверка: ").append(lastStatus).append('\n')
            append("Источник списка: ").append(if (r != null) "сервер (rev $remoteRev, режим ${remoteMode.ifEmpty { "replace" }})" else "вшитые модели").append('\n')
            append("Моделей в приложении: ").append(list.size).append('\n')
            append("Вшитые модели → сервер: ").append(bundledStatus).append('\n')
            append("Файл ").append(FILE_NAME).append(": ")
            append(if (f != null && f.isFile) "есть, ${f.length()} байт" else "нет")
        }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 10_000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) { lastStatus = "сервер ответил HTTP ${conn.responseCode}"; return null }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            lastStatus = "нет связи: ${e.javaClass.simpleName}: ${e.message}"
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun RemoteModel.toModel() = MainActivity.Model(name, provider, id, badge, desc, recommended, accent, isImage, descLoc = descLoc, isVideoVision = isVideo && !isImage,
        isVision = isVision && !isImage, isMultimodal = multimodal && !isImage, isUnverified = unverified, section = section)

    private val DESC_LANGS = listOf("en", "es", "pt", "kk", "uk", "be", "zh")

    private fun parseDescLoc(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (l in DESC_LANGS) o.optString(l).trim().take(160).takeIf { it.isNotEmpty() }?.let { out[l] = it }
        return out
    }

    fun parse(text: String): RemoteModelsResult? {
        return try {
            val o = JSONObject(text)
            val rev = o.optLong("rev", -1L)
            val pollMs = o.optLong("pollMs", 0L)
            val needBundled = o.optBoolean("needBundled", false)
            if (o.optBoolean("unchanged", false)) return RemoteModelsResult(rev, true, false, emptyList(), pollMs = pollMs, needBundled = needBundled)
            val arr = o.optJSONArray("models") ?: return null
            val seen = HashSet<String>()
            val out = ArrayList<RemoteModel>()
            for (i in 0 until minOf(arr.length(), 150)) {
                val m = arr.optJSONObject(i) ?: continue
                val provider = m.optString("provider").lowercase()
                val id = m.optString("id").trim()
                if (provider !in KNOWN_PROVIDERS) continue
                if (id.isEmpty() || id.length > 200 || !ID_RE.matches(id)) continue
                if (!seen.add("$provider/$id")) continue
                out.add(RemoteModel(
                    name = m.optString("name").trim().take(60).ifBlank { id },
                    provider = provider, id = id,
                    badge = m.optString("badge").take(8),
                    desc = m.optString("desc").take(160),
                    recommended = m.optBoolean("recommended", false),
                    accent = parseAccent(m.optString("accent")),
                    isImage = m.optBoolean("isImage", false),
                    isVideo = m.optBoolean("isVideo", false),
                    isVision = m.optBoolean("isVision", false),
                    multimodal = m.optBoolean("multimodal", false),
                    unverified = m.optBoolean("unverified", false),
                    section = m.optString("section", "").lowercase().takeIf { it == "paid" || it == "unstable" || it == "experimental" } ?: "",
                    descLoc = parseDescLoc(m.optJSONObject("descLoc")),
                    replaces = m.optString("replaces").trim().lowercase().take(260)
                ))
            }
            val mode = if (o.optString("mode") == RemoteModelsResult.MODE_MERGE) RemoteModelsResult.MODE_MERGE else RemoteModelsResult.MODE_REPLACE
            val hide = HashSet<String>()
            o.optJSONArray("hide")?.let { h ->
                for (i in 0 until minOf(h.length(), 300)) h.optString(i).trim().lowercase().takeIf { it.isNotEmpty() }?.let { hide.add(it) }
            }
            val order = ArrayList<String>()
            o.optJSONArray("order")?.let { a ->
                for (i in 0 until minOf(a.length(), 400)) a.optString(i).trim().lowercase().takeIf { it.isNotEmpty() }?.let { order.add(it) }
            }
            RemoteModelsResult(rev, false, o.optBoolean("useBuiltin", false), out, mode, hide, order, pollMs, needBundled)
        } catch (_: Exception) {
            null
        }
    }

    private val ID_RE = Regex("^[A-Za-z0-9_@.\\-:/]+$")

    private fun parseAccent(s: String): Int {
        val m = Regex("^#([0-9A-Fa-f]{6})$").matchEntire(s.trim()) ?: return 0xFF8AB4F8.toInt()
        return (0xFF000000L or m.groupValues[1].toLong(16)).toInt()
    }

    val bundled: List<MainActivity.Model> = listOf(
        MainActivity.Model("GPT-OSS 120B", "groq", "openai/gpt-oss-120b", "120", "Крупная open-source модель Groq", true, 0xFFFFB74D.toInt()),
        MainActivity.Model("Gemini 2.5 Flash", "gemini", "gemini-2.5-flash", "2.5", "Google AI Studio — основная Flash", true, 0xFF64B5F6.toInt(), isVideoVision = true, isVision = true),
        MainActivity.Model("Gemini 2.5 Flash-Lite", "gemini", "gemini-2.5-flash-lite", "2.5L", "Google — лёгкая и быстрая", true, 0xFF4FC3F7.toInt(), isVideoVision = true, isVision = true),
        MainActivity.Model("Gemini 2.0 Flash", "gemini", "gemini-2.0-flash", "2.0", "Google AI Studio — стабильная 2.0", true, 0xFF81D4FA.toInt(), isVideoVision = true, isVision = true),
        MainActivity.Model("Gemini 2.0 Flash-Lite", "gemini", "gemini-2.0-flash-lite", "2.0L", "Google — самая лёгкая 2.0", false, 0xFF80DEEA.toInt(), isVideoVision = true, isVision = true),
        MainActivity.Model("Gemini 3.5 Flash-Lite", "gemini", "gemini-3.5-flash-lite", "3.5L", "Google — Flash-Lite нового поколения", true, 0xFF90CAF9.toInt(), isVideoVision = true, isVision = true),
        MainActivity.Model("Nemotron 3N", "openrouter", "nvidia/nemotron-3-nano-30b-a3b:free", "3N", "Универсальная модель для любых задач", true, 0xFFFFB74D.toInt()),
        MainActivity.Model("Qwen3.6-27B", "groq", "qwen/qwen3.6-27b", "3.6", "Баланс скорости и качества (Groq)", true, 0xFF81C784.toInt()),
        MainActivity.Model("Qwen3.8-27B", "groq", "qwen/qwen3.8-27b", "3.8", "Groq — текст и изображения, режим размышления (preview)", true, 0xFF66BB6A.toInt(), isVision = true, isMultimodal = true),
        MainActivity.Model("GPT-OSS 20B", "groq", "openai/gpt-oss-20b", "20", "Быстрая open-source модель Groq", false, 0xFFFFB74D.toInt()),
        MainActivity.Model("GLM-4.7-Flash", "cloudflare", "@cf/zai-org/glm-4.7-flash", "GLM", "Cloudflare Workers AI — быстрый чат", true, 0xFFF48FB1.toInt()),
        MainActivity.Model("Gemma 4 26B", "cloudflare", "@cf/google/gemma-4-26b-a4b-it", "G4", "Cloudflare — Gemma 4 26B", true, 0xFF81C784.toInt()),
        MainActivity.Model("Llama 4 Scout", "cloudflare", "@cf/meta/llama-4-scout-17b-16e-instruct", "L4", "Cloudflare — Llama 4 Scout (текст + изображения)", true, 0xFFFFB74D.toInt(), isVision = true),
        MainActivity.Model("Qwen3 30B", "cloudflare", "@cf/qwen/qwen3-30b-a3b-fp8", "Q3", "Cloudflare — Qwen3 30B", true, 0xFF64B5F6.toInt()),
        MainActivity.Model("Llama 3.3 70B Fast", "cloudflare", "@cf/meta/llama-3.3-70b-instruct-fp8-fast", "L3.3", "Cloudflare — Llama 3.3 70B (FP8, быстрая)", true, 0xFFFFA726.toInt()),
        MainActivity.Model("QwQ 32B", "cloudflare", "@cf/qwen/qwq-32b", "QwQ", "Cloudflare — reasoning-модель Qwen", false, 0xFF42A5F5.toInt()),
        MainActivity.Model("DeepSeek R1 Distill 32B", "cloudflare", "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b", "R1", "Cloudflare — R1, дистиллированная в Qwen 32B", false, 0xFF4DD0E1.toInt()),
        MainActivity.Model("Nemotron 3 120B", "cloudflare", "@cf/nvidia/nemotron-3-120b-a12b", "N120", "Cloudflare — NVIDIA Nemotron 3 для агентных задач", false, 0xFF9CCC65.toInt()),
        MainActivity.Model("Mistral Small 3.1 24B", "cloudflare", "@cf/mistralai/mistral-small-3.1-24b-instruct", "MS3", "Cloudflare — Mistral Small 3.1 (текст + изображения)", false, 0xFFFF8A65.toInt(), isVision = true),
        MainActivity.Model("Llama 3.1 8B", "cloudflare", "@cf/meta/llama-3.1-8b-instruct-fp8", "8B", "Cloudflare — Llama 3.1 8B (FP8)", false, 0xFFFFCC80.toInt()),
        MainActivity.Model("Llama 3.2 3B", "cloudflare", "@cf/meta/llama-3.2-3b-instruct", "3B", "Cloudflare — компактная Llama 3.2", false, 0xFFFFE0B2.toInt()),
        MainActivity.Model("Llama 3.2 1B", "cloudflare", "@cf/meta/llama-3.2-1b-instruct", "1B", "Cloudflare — самая лёгкая Llama 3.2", false, 0xFFFFF3E0.toInt()),
        MainActivity.Model("Granite 4.0 H Micro", "cloudflare", "@cf/ibm-granite/granite-4.0-h-micro", "GR", "Cloudflare — IBM Granite, вызов функций", false, 0xFF7986CB.toInt()),
        MainActivity.Model("Gemma SEA-LION v4 27B", "cloudflare", "@cf/aisingapore/gemma-sea-lion-v4-27b-it", "SEA", "Cloudflare — Gemma для языков Юго-Восточной Азии", false, 0xFF66BB6A.toInt()),
        MainActivity.Model("PhoneLLM Mini", "cloudflare", "@cf/daily-co/phonellm-mini-alpha-2", "PH", "Cloudflare — компактная диалоговая модель (alpha)", false, 0xFFBA68C8.toInt(), isUnverified = true),
        MainActivity.Model("Clef", "cloudflare", "@cf/cloudflare/clef", "Clef", "Не чат: модель решений Cloudflare (System One API)", false, 0xFFF6821F.toInt(), isUnverified = true, section = "experimental"),
        MainActivity.Model("Clef Flash", "cloudflare", "@cf/cloudflare/clef-flash", "Clef⚡", "Не чат: быстрая модель решений Cloudflare", false, 0xFFFB923C.toInt(), isUnverified = true, section = "experimental"),
        MainActivity.Model("Clef Omni", "cloudflare", "@cf/cloudflare/clef-omni", "Clef Ω", "Не чат: модель решений (текст, изображения, аудио, видео)", false, 0xFFFDBA74.toInt(), isUnverified = true, section = "experimental"),
        MainActivity.Model("Clairic", "cloudflare", "@cf/cloudflare/clairic", "Clair", "Не чат: извлечение типизированных значений из текста/картинок", false, 0xFFFED7AA.toInt(), isUnverified = true, section = "experimental"),
        MainActivity.Model("FLUX.2 klein 4B", "cloudflare", "@cf/black-forest-labs/flux-2-klein-4b", "4B", "Генерация изображений (быстро)", true, 0xFFE1BEE7.toInt(), true),
        MainActivity.Model("FLUX.2 klein 9B", "cloudflare", "@cf/black-forest-labs/flux-2-klein-9b", "9B", "Генерация изображений (качество)", true, 0xFFCE93D8.toInt(), true),
        MainActivity.Model("FLUX.2 dev", "cloudflare", "@cf/black-forest-labs/flux-2-dev", "DEV", "Генерация изображений (детально)", false, 0xFFBA68C8.toInt(), true),
        MainActivity.Model("FLUX.1 schnell", "cloudflare", "@cf/black-forest-labs/flux-1-schnell", "F1", "Быстрая генерация FLUX.1", true, 0xFFAB47BC.toInt(), true),
        MainActivity.Model("Nemotron 3.5 Super VL", "openrouter", "nvidia/nemotron-3.5-super-vl:free", "VL", "Мультимодальная: текст, изображения, документы и видео", true, 0xFF76B900.toInt(), isVideoVision = true, isVision = true, isMultimodal = true),
        MainActivity.Model("Nemotron 3 Ultra 550B", "openrouter", "nvidia/nemotron-3-ultra-550b-a55b:free", "550B", "Флагманская reasoning-модель NVIDIA (MoE 55B активных)", true, 0xFF8BC34A.toInt()),
        MainActivity.Model("Nemotron 3 Super 120B", "openrouter", "nvidia/nemotron-3-super-120b-a12b:free", "120B", "Агентные задачи и длинный контекст", false, 0xFFAED581.toInt()),
        MainActivity.Model("Nemotron 3.5 Lightning", "openrouter", "nvidia/nemotron-3.5-lightning:free", "3.5L", "Очень быстрая MoE-модель (3B активных)", false, 0xFFC5E1A5.toInt()),
        MainActivity.Model("MiniMax M3.1 Flash", "openrouter", "minimax/minimax-m3.1-flash:free", "M3.1", "Быстрая мультимодальная модель MiniMax, контекст 1M", false, 0xFFEF5350.toInt(), isVision = true, isMultimodal = true, isUnverified = true),
        MainActivity.Model("Gemma-4-26b", "openrouter", "google/gemma-4-26b-a4b-it:free", "4.26", "Высокая производительность", false, 0xFF81C784.toInt()),
        MainActivity.Model("Gemma-4-31b", "openrouter", "google/gemma-4-31b-it:free", "4.31", "Высокая производительность и точность", false, 0xFF81C784.toInt()),
        MainActivity.Model("Nemotron-Nano-9B", "openrouter", "nvidia/nemotron-nano-9b-v2:free", "9B", "Оптимальна для быстрых ответов", false, 0xFFFFB74D.toInt()),
        MainActivity.Model("Nemotron-Omni-30B", "openrouter", "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free", "Omni", "Мультимодальная reasoning-модель (изображения, видео, аудио)", false, 0xFFFF8A65.toInt(), isVideoVision = true, isVision = true, isMultimodal = true),
        MainActivity.Model("Ling-3.0-Tiny", "openrouter", "inclusionai/ling-3.0-tiny:free", "3.0", "Легковесная модель для простых задач", false, 0xFFBA68C8.toInt()),
        MainActivity.Model("LFM-2.5-2.6B", "openrouter", "liquid/lfm-2.5-2.6b:free", "2.6", "Компактная модель Liquid", false, 0xFF90CAF9.toInt()),
        MainActivity.Model("DeepSeek V4 Flash", "llm7", "DeepSeek-V4-Flash-0731", "V4F", "LLM7 — DeepSeek V4 Flash (0731)", false, 0xFF4FC3F7.toInt()),
        MainActivity.Model("GLM-5.3 Flash", "llm7", "GLM-5.3-Flash", "5.3F", "LLM7 — GLM 5.3 Flash", false, 0xFFF48FB1.toInt()),
        MainActivity.Model("MiniMax M2.7", "llm7", "minimax-m2.7", "M2.7", "LLM7 — MiniMax M2.7", false, 0xFFEF5350.toInt()),
        MainActivity.Model("Mistral Nemo 2407", "llm7", "mistral-Nemo-Instruct-2407", "Nemo", "LLM7 — Mistral Nemo Instruct", false, 0xFFFF8A65.toInt()),
        MainActivity.Model("Nemotron 3 Nano 30B", "llm7", "nemotron-3-nano:30b", "N3N", "LLM7 — NVIDIA Nemotron 3 Nano 30B", false, 0xFF9CCC65.toInt())
    )

    fun isSystemOne(provider: String, id: String): Boolean {
        if (!provider.equals("cloudflare", true)) return false
        val t = id.lowercase()
        return t.startsWith("@cf/cloudflare/clef") || t.startsWith("@cf/cloudflare/clairic")
    }

    private const val MIN_BUNDLED_POST_GAP_MS = 10L * 60 * 1000
    @Volatile private var lastBundledPost = 0L
    @Volatile private var bundledStatus = "сервер не просил"

    fun bundledJson(list: List<MainActivity.Model> = bundled): String {
        val arr = org.json.JSONArray()
        for (m in list) {
            arr.put(JSONObject()
                .put("name", m.name).put("provider", m.provider).put("id", m.id)
                .put("badge", m.badge).put("desc", m.desc)
                .put("recommended", m.recommended && m.section.isEmpty())
                .put("accent", String.format("#%06X", m.accent and 0xFFFFFF))
                .put("isImage", m.isImage)
                .put("isVideo", m.isVideoVision && !m.isImage)
                .put("isVision", m.isVision && !m.isImage)
                .put("multimodal", m.isMultimodal && !m.isImage)
                .put("unverified", m.isUnverified)
                .put("section", m.section))
        }
        return arr.toString()
    }

    private val bundledHashLazy by lazy { bundledHash() }
    private fun bundledHashCached(): String = bundledHashLazy

    fun bundledHash(json: String = bundledJson()): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun appVersionCode(app: Context): Long = try {
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (_: Exception) { 0L }

    private fun reportBundledIfNeeded(app: Context) {
        val now = System.currentTimeMillis()
        if (now - lastBundledPost < MIN_BUNDLED_POST_GAP_MS) return
        lastBundledPost = now
        var conn: HttpURLConnection? = null
        try {
            val json = bundledJson()
            val body = JSONObject().put("hash", bundledHash(json)).put("appVersion", appVersionCode(app))
                .put("models", org.json.JSONArray(json)).toString()
            conn = (URL(REMOTE_URL.trimEnd('/') + "/bundled").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 8_000; readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            bundledStatus = "отправлено на сервер: HTTP $code (${bundled.size} шт.)"
        } catch (e: Exception) {
            bundledStatus = "не отправлено: ${e.javaClass.simpleName}"
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
