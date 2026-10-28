package com.youraicqa.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiClient {
    suspend fun listModels(providerUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val base = providerUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/models")) base else "$base/models"
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 15000
        if (apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $apiKey")
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (c.responseCode !in 200..299) {
            throw Exception("HTTP ${c.responseCode}: ${text.take(300)}")
        }
        val json = JSONObject(text)
        val arr = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        (0 until arr.length()).mapNotNull { i ->
            val item = arr.opt(i)
            when (item) {
                is JSONObject -> item.optString("id").ifBlank { null }
                is String -> item
                else -> null
            }
        }
    }

    suspend fun chat(
        provider: String,
        model: String,
        apiKey: String,
        messages: JSONArray,
        deviceId: String,
        cloudflareAccountId: String = "",
        maxTokens: Int = 2800,
        opts: ChatOpts? = null
    ): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        var msgs = messages
        var noToolRetried = false
        var attempt = 0
        while (attempt <= 1) {
            try {
                if (apiKey.isBlank() && !ApiKeyGate.keyOptional(provider)) {
                    throw Exception("Для этой модели не задан API-ключ. Добавьте свой ключ в настройках или настройте провайдера.")
                }
                return@withContext callDirect(provider, model, apiKey, msgs, cloudflareAccountId, maxTokens, opts)
            } catch (e: Exception) {
                lastError = e
                val msg = e.message ?: ""
                val is429 = msg.contains("429") || msg.contains("rate-limited", ignoreCase = true) ||
                        msg.contains("rate limit", ignoreCase = true)
                if (is429 && attempt == 0) {
                    delay(1500)
                    attempt++
                    continue
                }
                val isToolUseFailed = msg.contains("tool_use_failed", ignoreCase = true) ||
                        msg.contains("model called a tool", ignoreCase = true)
                if (isToolUseFailed && !noToolRetried) {
                    noToolRetried = true
                    msgs = JSONArray().also { arr ->
                        for (i in 0 until messages.length()) arr.put(messages.get(i))
                        arr.put(JSONObject().put("role", "system").put("content",
                            "There are no tools (web.run, browser, search and any others must not be called). " +
                                "Do not call functions — answer in plain text, relying on the data already given in the conversation."))
                    }
                    continue
                }
                throw friendlyError(e)
            }
        }
        throw friendlyError(lastError ?: Exception("Неизвестная ошибка"))
    }

    suspend fun chatCustom(
        providerUrl: String,
        model: String,
        apiKey: String,
        messages: JSONArray,
        maxTokens: Int = 2800,
        opts: ChatOpts? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val url = if (providerUrl.contains("/chat/completions")) providerUrl
                else providerUrl.trimEnd('/') + "/chat/completions"
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .put("temperature", 0.7)
            val headers = if (apiKey.isNotBlank()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
            postChat(url, body, headers, model, opts)
        } catch (e: Exception) {
            throw friendlyError(e)
        }
    }

    private const val FLUX_PROMPT_SAFE_LEN = 900
    private const val FLUX_PROMPT_RETRY_LEN = 220

    suspend fun generateImageCustom(
        providerUrl: String,
        model: String,
        apiKey: String,
        prompt: String,
        api: String,
        refImagesB64: List<String> = emptyList(),
        size: String = "",
        steps: Int = 0,
        negativePrompt: String = ""
    ): String = withContext(Dispatchers.IO) {
        try {
            val base = providerUrl.trim().trimEnd('/')
            val wh = Regex("^\\s*(\\d{2,4})\\s*[xX×*]\\s*(\\d{2,4})\\s*$").find(size)
            val sizeW = wh?.groupValues?.get(1)?.toIntOrNull()
            val sizeH = wh?.groupValues?.get(2)?.toIntOrNull()
            val headers = HashMap<String, String>()
            if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
            val text: String
            if (api == ModelModes.API_A1111) {
                val root = base.removeSuffix("/v1").removeSuffix("/sdapi/v1/txt2img").trimEnd('/')
                val ref = refImagesB64.firstOrNull()
                val url = root + if (ref != null) "/sdapi/v1/img2img" else "/sdapi/v1/txt2img"
                val body = JSONObject().put("prompt", prompt).put("steps", if (steps > 0) steps.coerceAtMost(150) else 20)
                    .put("width", sizeW ?: 768).put("height", sizeH ?: 768)
                if (negativePrompt.isNotBlank()) body.put("negative_prompt", negativePrompt)
                if (ref != null) body.put("init_images", JSONArray().put(ref)).put("denoising_strength", 0.6)
                if (model.isNotBlank() && model != "default") body.put("override_settings", JSONObject().put("sd_model_checkpoint", model))
                text = postJsonRaw(url, body.toString(), headers, 300000)
                val arr = JSONObject(text).optJSONArray("images")
                val first = arr?.optString(0).orEmpty()
                if (first.isBlank()) throw Exception("Сервер не вернул изображение")
                return@withContext first.substringAfter("base64,")
            }
            val url = if (base.endsWith("/images/generations")) base else {
                val b0 = if (base.contains("/chat/completions")) base.substringBefore("/chat/completions") else base
                val bare = try { URL(b0).path.isNullOrEmpty() } catch (_: Exception) { false }
                (if (bare) "$b0/v1" else b0) + "/images/generations"
            }
            val body = JSONObject().put("model", model).put("prompt", prompt).put("n", 1)
                .put("size", if (sizeW != null && sizeH != null) "${sizeW}x${sizeH}" else "1024x1024")
                .put("response_format", "b64_json")
            text = postJsonRaw(url, body.toString(), headers, 300000)
            val json = JSONObject(text)
            val item = json.optJSONArray("data")?.optJSONObject(0)
                ?: throw Exception("Сервер не вернул изображение")
            val b64 = item.optString("b64_json")
            if (b64.isNotBlank()) return@withContext b64.substringAfter("base64,")
            val imgUrl = item.optString("url")
            if (imgUrl.isNotBlank()) {
                val c = URL(imgUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 20000; c.readTimeout = 120000
                val bytes = c.inputStream.use { it.readBytes() }
                return@withContext android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
            throw Exception("Сервер не вернул изображение")
        } catch (e: Exception) {
            throw friendlyError(e)
        }
    }

    private fun postJsonRaw(url: String, body: String, headers: Map<String, String>, readTimeoutMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = readTimeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("HTTP $code: ${text.take(400)}")
        return text
    }

    private fun isFlux(modelPath: String) = modelPath.contains("flux", ignoreCase = true)

    private fun trimPrompt(prompt: String, maxLen: Int): String {
        if (prompt.length <= maxLen) return prompt
        val cut = prompt.substring(0, maxLen)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > maxLen / 2) cut.substring(0, lastSpace) else cut).trimEnd()
    }

    private fun isServerSideFailure(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("http 5") || m.contains("500") || m.contains("502") ||
            m.contains("503") || m.contains("504") || m.contains("internal error")
    }

    suspend fun generateImage(
        model: String,
        prompt: String,
        apiToken: String,
        accountId: String,
        deviceId: String = "",
        refImagesB64: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val modelPath = model.removePrefix("/")
        val flux = isFlux(modelPath)
        val safePrompt = if (flux) trimPrompt(prompt, FLUX_PROMPT_SAFE_LEN) else prompt

        suspend fun attempt(p: String): String {
            if (apiToken.isBlank() || accountId.isBlank()) {
                throw Exception("Для генерации изображений нужен собственный Cloudflare API Token и Account ID.")
            }
            return generateImageDirect(modelPath, p, apiToken, accountId, refImagesB64)
        }

        try {
            return@withContext attempt(safePrompt)
        } catch (e: Exception) {
            if (flux && isServerSideFailure(e) && safePrompt.length > FLUX_PROMPT_RETRY_LEN) {
                delay(400)
                val shortPrompt = trimPrompt(safePrompt, FLUX_PROMPT_RETRY_LEN)
                return@withContext try {
                    attempt(shortPrompt)
                } catch (_: Exception) {
                    throw e
                }
            }
            throw e
        }
    }

    private fun generateImageDirect(
        modelPath: String,
        prompt: String,
        apiToken: String,
        accountId: String,
        refImagesB64: List<String> = emptyList()
    ): String {
        val url =
            "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$modelPath"
        val needsMultipart = modelPath.contains("flux-2")
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 20000
        c.readTimeout = 180000
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $apiToken")

        if (needsMultipart || refImagesB64.isNotEmpty()) {
            val boundary = "Yourbit AI" + System.currentTimeMillis()
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val os = c.outputStream
            fun part(name: String, value: String) {
                os.write("--$boundary\r\n".toByteArray())
                os.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                os.write(value.toByteArray(Charsets.UTF_8))
                os.write("\r\n".toByteArray())
            }
            fun partFile(name: String, filename: String, bytes: ByteArray, mime: String) {
                os.write("--$boundary\r\n".toByteArray())
                os.write(
                    ("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n").toByteArray()
                )
                os.write("Content-Type: $mime\r\n\r\n".toByteArray())
                os.write(bytes)
                os.write("\r\n".toByteArray())
            }
            part("prompt", prompt)
            part("width", "1024")
            part("height", "1024")
            if (modelPath.contains("flux-2-dev")) {
                part("steps", "20")
            } else if (modelPath.contains("flux-2")) {
            } else if (!needsMultipart) {
                part("steps", "4")
            }
            refImagesB64.take(4).forEachIndexed { idx, b64 ->
                try {
                    val raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    partFile("input_image_$idx", "ref$idx.jpg", raw, "image/jpeg")
                } catch (_: Exception) { }
            }
            os.write("--$boundary--\r\n".toByteArray())
            os.flush()
        } else {
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("prompt", prompt)
                .put("steps", 4)
                .toString()
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.readBytes() ?: ByteArray(0)
        val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "" }
        if (code !in 200..299) {
            throw friendlyError(Exception("HTTP $code: ${text.take(400)}"))
        }
        try {
            val json = JSONObject(text)
            val result = json.opt("result")
            when (result) {
                is JSONObject -> {
                    val img = result.optString("image")
                        .ifBlank { result.optString("b64_json") }
                    if (img.isNotBlank()) return img
                }
                is String -> if (result.isNotBlank()) return result
            }
            val top = json.optString("image")
            if (top.isNotBlank()) return top
        } catch (_: Exception) { }
        if (bytes.isNotEmpty() && bytes[0] != '{'.code.toByte()) {
            return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
        throw Exception("Cloudflare не вернул изображение")
    }

    private fun friendlyError(e: Exception): Exception {
        val raw = e.message ?: "Неизвестная ошибка"
        val low = raw.lowercase()
        return when {
            low.contains("429") || low.contains("rate-limited") || low.contains("rate limit") ->
                Exception(
                    "Модель сейчас перегружена (лимит запросов). " +
                        "Подождите 10–30 секунд или выберите другую модель."
                )
            low.contains("neuron") || low.contains("capacity") || low.contains("quota") ->
                Exception(
                    "Лимит Workers AI (neurons) исчерпан или модель перегружена. " +
                        "Подождите или выберите более лёгкую модель (GLM / FLUX.1)."
                )
            low.contains("timeout") || low.contains("timed out") ->
                Exception(
                    "Таймаут: модель слишком долго отвечает (часто у Gemma/Llama). " +
                        "Попробуйте ещё раз или выберите GLM / Qwen."
                )
            (low.contains("401") || low.contains("403") || low.contains("9109") || low.contains("10000")) &&
                (low.contains("auth") || low.contains("unauthorized") || low.contains("forbidden") ||
                    low.contains("authentication") || low.contains("9109") || low.contains("10000")) ->
                Exception(
                    "Ключ отклонён Cloudflare (ошибка авторизации). Проверьте, что API Token имеет право " +
                        "\"Workers AI: Edit\" и что Account ID указан верно."
                )
            low.contains("404") || low.contains("no endpoints") || low.contains("7003") ->
                Exception("Модель недоступна у провайдера. Выберите другую. ${raw.take(160)}")
            low.contains("http 5") || low.contains("internal error") ->
                Exception(
                    "Модель не справилась с генерацией (внутренняя ошибка провайдера) — часто " +
                        "происходит у FLUX на сложных/длинных промптах или необычных референс-" +
                        "картинках. Yourbit AI уже пробует один раз с упрощённым промптом автоматически; " +
                        "если не помогло — попробуйте сформулировать проще или выбрать другую модель. " +
                        raw.take(160)
                )
            else -> Exception(raw.take(400))
        }
    }

    private fun callDirect(
        provider: String,
        model: String,
        apiKey: String,
        messages: JSONArray,
        cloudflareAccountId: String,
        maxTokens: Int = 2800,
        opts: ChatOpts? = null
    ): String {
        if (provider == "cloudflare") {
            if (BuiltinModels.isSystemOne(provider, model)) {
                throw Exception("Clef / Clairic — не чат-модели: у них отдельный System One API (state + questions/fields). Для диалога выберите другую модель.")
            }
            if (cloudflareAccountId.isBlank()) {
                throw Exception("Нет Account ID. Включите «собственный API-ключ» или укажите Cloudflare Account ID.")
            }
            val modelPath = model.removePrefix("/")
            val isReasoningModel = modelPath.contains("glm", true) || modelPath.contains("qwen", true) ||
                modelPath.contains("deepseek-r1", true) || modelPath.contains("nemotron", true)
            val effectiveMaxTokens = if (isReasoningModel) maxOf(maxTokens * 3, 9000) else maxTokens
            val chatUrl =
                "https://api.cloudflare.com/client/v4/accounts/$cloudflareAccountId/ai/v1/chat/completions"
            val chatBody = JSONObject()
                .put("model", modelPath)
                .put("messages", messages)
                .put("max_tokens", effectiveMaxTokens)
                .toString()
            try {
                return postJsonCloudflare(chatUrl, chatBody, apiKey)
            } catch (e1: Exception) {
                val runUrl =
                    "https://api.cloudflare.com/client/v4/accounts/$cloudflareAccountId/ai/run/$modelPath"
                val runBody = JSONObject()
                    .put("messages", messages)
                    .put("max_tokens", effectiveMaxTokens)
                    .toString()
                try {
                    return postJsonCloudflare(runUrl, runBody, apiKey)
                } catch (e2: Exception) {
                    val bothEmpty = (e1.message?.contains("Пустой ответ") == true) &&
                        (e2.message?.contains("Пустой ответ") == true)
                    if (isReasoningModel && bothEmpty) {
                        val retryBody = JSONObject()
                            .put("model", modelPath)
                            .put("messages", messages)
                            .put("max_tokens", effectiveMaxTokens * 2)
                            .toString()
                        try {
                            return postJsonCloudflare(chatUrl, retryBody, apiKey)
                        } catch (e3: Exception) {
                            throw Exception("chat/completions: ${e1.message} | ai/run: ${e2.message} | retry: ${e3.message}")
                        }
                    }
                    throw Exception("chat/completions: ${e1.message} | ai/run: ${e2.message}")
                }
            }
        }
        val (url, headers) = when (provider) {
            "openrouter" -> "https://openrouter.ai/api/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey",
                "HTTP-Referer" to "https://t.me/Yourbit AIreborn_bot",
                "X-Title" to "Yourbit AI"
            )
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "groq" -> "https://api.groq.com/openai/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "llm7" -> "https://api.llm7.io/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer ${apiKey.ifBlank { "unused" }}"
            )
            "pollinations" -> "https://gen.pollinations.ai/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "mistral" -> "https://api.mistral.ai/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "cohere" -> "https://api.cohere.ai/compatibility/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "openai" -> "https://api.openai.com/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "qwen" -> "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "deepseek" -> "https://api.deepseek.com/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "nvidia" -> "https://integrate.api.nvidia.com/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
            "anthropic" -> "https://api.anthropic.com/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey",
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
            else -> "https://router.huggingface.co/v1/chat/completions" to mapOf(
                "Authorization" to "Bearer $apiKey"
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.7)
        if (provider == "openai" && isOpenAiReasoning(model)) {
            body.remove("max_tokens"); body.put("max_completion_tokens", maxTokens); body.remove("temperature")
        }
        return postChat(url, body, headers, model, opts)
    }

    private fun isOpenAiReasoning(model: String): Boolean {
        val m = model.lowercase()
        return m.startsWith("gpt-5") || (m.length > 1 && m[0] == 'o' && m[1].isDigit())
    }

    private fun applyReasoning(body: JSONObject, url: String, model: String, effort: String) {
        val u = url.lowercase()
        val m = model.lowercase()
        when {
            u.contains("openrouter.ai") -> body.put("reasoning", JSONObject().put("effort", effort))
            u.contains("generativelanguage.googleapis.com") ->
                if (m.contains("gemini-2.5") || m.contains("gemini-3")) body.put("reasoning_effort", effort)
            u.contains("api.groq.com") -> if (m.contains("gpt-oss")) body.put("reasoning_effort", effort)
            u.contains("api.openai.com") -> if (isOpenAiReasoning(model)) body.put("reasoning_effort", effort)
        }
    }

    private fun postChat(url: String, body: JSONObject, headers: Map<String, String>, model: String, opts: ChatOpts?): String {
        val effort = opts?.effort
        if (!effort.isNullOrBlank()) {
            val withEffort = JSONObject(body.toString())
            applyReasoning(withEffort, url, model, effort)
            if (withEffort.length() != body.length()) {
                try {
                    return postJsonChat(url, withEffort.toString(), headers, opts)
                } catch (e: Exception) {
                    val low = (e.message ?: "").lowercase()
                    val paramRejected = (low.contains("http 400") || low.contains("http 422")) &&
                        (low.contains("reasoning") || low.contains("effort") || low.contains("thinking"))
                    if (!paramRejected) throw e
                }
            }
        }
        return postJsonChat(url, body.toString(), headers, opts)
    }

    private fun postJsonCloudflare(url: String, body: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30000
        c.readTimeout = 180000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $token")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (c.responseCode !in 200..299) {
            throw Exception("HTTP ${c.responseCode}: ${text.take(500)}")
        }
        val json = JSONObject(text)

        fun textFromMessage(message: JSONObject?): String? {
            if (message == null) return null
            val content = ServerLogic.extractChat(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", message)))).text.trim()
            if (content.isNotBlank()) return content
            val reasoningContent = (message.opt("reasoning_content") as? String).orEmpty().trim()
            if (reasoningContent.isNotBlank()) return reasoningContent
            val reasoning = (message.opt("reasoning") as? String).orEmpty().trim()
            if (reasoning.isNotBlank()) return reasoning
            return null
        }

        val result = json.optJSONObject("result")
        if (result != null) {
            val resp = (result.opt("response") as? String).orEmpty()
            if (resp.isNotBlank()) return resp
            val choices = result.optJSONArray("choices")
            val fromResult = textFromMessage(choices?.optJSONObject(0)?.optJSONObject("message"))
            if (fromResult != null) return fromResult
        }
        val topChoices = json.optJSONArray("choices")
        val topLevel = textFromMessage(topChoices?.optJSONObject(0)?.optJSONObject("message"))
        return topLevel ?: throw Exception("Пустой ответ от модели (не хватило max_tokens на reasoning)")
    }

    private fun postJsonChat(url: String, body: String, headers: Map<String, String>, opts: ChatOpts? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (c.responseCode !in 200..299) {
            throw Exception("HTTP ${c.responseCode}: ${text.take(500)}")
        }
        val json = try { JSONObject(text.trim()) } catch (_: Exception) {
            throw Exception("Ответ провайдера не похож на JSON: " + text.trim().replace(Regex("\\s+"), " ").take(160))
        }
        val x = ServerLogic.extractChat(json)
        opts?.record(x)
        if (x.text.isNotBlank()) return x.text
        if (!x.hasChoice) {
            val err = json.opt("error")
            val msg = when (err) {
                is JSONObject -> err.opt("message") as? String
                is String -> err
                else -> null
            }
            if (!msg.isNullOrBlank()) throw Exception("Ошибка провайдера: " + msg.take(300))
        }
        if (opts != null && opts.tolerant) return ""
        val detail = ServerLogic.emptyDetail(x)
        throw Exception("Пустой ответ от модели" + if (detail.isBlank()) "" else " ($detail)")
    }

    data class SearchResult(val title: String, val snippet: String, val url: String, val source: String)

    private fun guessFandomHost(query: String): String? = com.youraicqa.app.linkimporter.FandomHosts.resolve(query)

    private fun httpGetJson(url: String): JSONObject = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "Yourbit AI-Android/1.0")
        c.connectTimeout = 7000
        c.readTimeout = 7000
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        c.disconnect()
        JSONObject(text)
    } catch (_: Exception) {
        JSONObject()
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun stripHtml(s: String): String = s.replace(Regex("<[^>]*>"), "").trim()

    private val NEWS_QUERY_MARKERS = listOf(
        "новост", "что происходит", "что случилось", "последние события",
        "свежие новости", "сегодня в мире", "news", "breaking", "headline", "happening now",
        "latest on", "what happened"
    )

    fun looksLikeNewsQuery(query: String): Boolean {
        val q = query.lowercase()
        return NEWS_QUERY_MARKERS.any { q.contains(it) }
    }

    private fun newsSearch(query: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val url = "https://news.google.com/rss/search?q=${enc(query)}&hl=ru&gl=RU&ceid=RU:ru"
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Yourbit AI-Android/1.0")
            c.connectTimeout = 7000
            c.readTimeout = 7000
            val xml = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            val linkRegex = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
            val pubDateRegex = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
            val sourceRegex = Regex("<source[^>]*>(.*?)</source>", RegexOption.DOT_MATCHES_ALL)
            fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()
            for (m in itemRegex.findAll(xml)) {
                if (results.size >= maxResults) break
                val block = m.groupValues[1]
                val rawTitle = titleRegex.find(block)?.groupValues?.get(1)?.let(::unescape) ?: continue
                val link = linkRegex.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
                val pubDate = pubDateRegex.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
                val feedSource = sourceRegex.find(block)?.groupValues?.get(1)?.let(::unescape)
                val dashIdx = rawTitle.lastIndexOf(" - ")
                val headline = if (dashIdx > 0) rawTitle.substring(0, dashIdx) else rawTitle
                val publication = feedSource ?: (if (dashIdx > 0) rawTitle.substring(dashIdx + 3) else "Google News")
                val snippet = if (pubDate.isNotBlank()) "$publication · $pubDate" else publication
                results.add(SearchResult(headline, snippet, link, "Новости · $publication"))
            }
        } catch (_: Exception) {}
        return results
    }

    private fun bingNewsSearch(query: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val url = "https://www.bing.com/news/search?q=${enc(query)}&format=RSS&setlang=ru-RU&cc=RU"
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "Yourbit AI-Android/1.0")
            c.connectTimeout = 7000
            c.readTimeout = 7000
            val xml = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            val itemRegex = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            val titleRegex = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            val linkRegex = Regex("<link>(.*?)</link>", RegexOption.DOT_MATCHES_ALL)
            val pubDateRegex = Regex("<pubDate>(.*?)</pubDate>", RegexOption.DOT_MATCHES_ALL)
            val sourceRegex = Regex("<(?:News:Source|source)[^>]*>(.*?)</(?:News:Source|source)>", RegexOption.DOT_MATCHES_ALL)
            fun stripCdata(s: String) = s.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
            fun unescape(s: String) = stripCdata(s).replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim()
            for (m in itemRegex.findAll(xml)) {
                if (results.size >= maxResults) break
                val block = m.groupValues[1]
                val headline = titleRegex.find(block)?.groupValues?.get(1)?.let(::unescape) ?: continue
                val link = linkRegex.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
                val pubDate = pubDateRegex.find(block)?.groupValues?.get(1)?.let(::unescape).orEmpty()
                val publication = sourceRegex.find(block)?.groupValues?.get(1)?.let(::unescape) ?: "Bing News"
                val snippet = if (pubDate.isNotBlank()) "$publication · $pubDate" else publication
                results.add(SearchResult(headline, snippet, link, "Новости · $publication"))
            }
        } catch (_: Exception) {}
        return results
    }

    suspend fun webSearch(query: String, maxResults: Int = 6): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SearchResult>()

        if (looksLikeNewsQuery(query)) {
            val fromGoogle = newsSearch(query, maxResults = 3)
            val fromBing = bingNewsSearch(query, maxResults = 3)
            val seenLinks = mutableSetOf<String>()
            val seenTitles = mutableSetOf<String>()
            for (r in fromGoogle + fromBing) {
                val linkKey = r.url.trim()
                val titleKey = r.title.trim().lowercase()
                if (linkKey.isNotBlank() && !seenLinks.add(linkKey)) continue
                if (linkKey.isBlank() && !seenTitles.add(titleKey)) continue
                results.add(r)
                if (results.size >= 4) break
            }
        }

        for (lang in listOf("ru", "en")) {
            try {
                val searchUrl = "https://$lang.wikipedia.org/w/api.php?action=query&list=search" +
                    "&srsearch=${enc(query)}&format=json&srlimit=1&utf8=1"
                val hits = httpGetJson(searchUrl).optJSONObject("query")?.optJSONArray("search")
                val title = hits?.optJSONObject(0)?.optString("title")
                if (!title.isNullOrBlank()) {
                    val summaryUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title)}"
                    val summary = httpGetJson(summaryUrl)
                    val extract = summary.optString("extract")
                    val pageUrl = summary.optJSONObject("content_urls")
                        ?.optJSONObject("desktop")?.optString("page")
                        ?: "https://$lang.wikipedia.org/wiki/${enc(title)}"
                    if (extract.isNotBlank()) {
                        results.add(
                            SearchResult(
                                title, extract, pageUrl,
                                if (lang == "ru") "Wikipedia (RU)" else "Wikipedia (EN)"
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        try {
            val url = "https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1"
            val json = httpGetJson(url)
            val abstractText = json.optString("AbstractText")
            if (abstractText.isNotBlank()) {
                results.add(
                    SearchResult(
                        json.optString("Heading").ifBlank { query },
                        abstractText,
                        json.optString("AbstractURL"),
                        "DuckDuckGo"
                    )
                )
            }
            val related = json.optJSONArray("RelatedTopics")
            if (related != null) {
                var added = 0
                for (i in 0 until related.length()) {
                    if (added >= 2) break
                    val item = related.optJSONObject(i) ?: continue
                    val text = item.optString("Text")
                    val href = item.optString("FirstURL")
                    if (text.isNotBlank()) {
                        results.add(SearchResult(text.take(60), text, href, "DuckDuckGo"))
                        added++
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            val host = guessFandomHost(query)
            if (host != null) {
                val url = "https://$host.fandom.com/api.php?action=query&list=search" +
                    "&srsearch=${enc(query)}&format=json&srlimit=2&utf8=1"
                val hits = httpGetJson(url).optJSONObject("query")?.optJSONArray("search")
                if (hits != null) {
                    for (i in 0 until hits.length()) {
                        val h = hits.optJSONObject(i) ?: continue
                        val title = h.optString("title")
                        val snippet = stripHtml(h.optString("snippet"))
                        if (title.isNotBlank()) {
                            results.add(
                                SearchResult(
                                    title, snippet,
                                    "https://$host.fandom.com/wiki/${enc(title.replace(" ", "_"))}",
                                    "Fandom"
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        results.take(maxResults)
    }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun httpGetText(url: String, ua: String = BROWSER_UA, maxBytes: Int = 600_000): String = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "ru,en;q=0.8")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val st = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val bytes = st?.use { readLimited(it, maxBytes) } ?: ByteArray(0)
        c.disconnect()
        String(bytes, Charsets.UTF_8)
    } catch (_: Exception) { "" }

    private fun readLimited(st: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = st.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun unescapeHtml(s: String): String {
        var t = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ").replace("&laquo;", "«").replace("&raquo;", "»")
            .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…")
        t = Regex("&#(\\d{2,5});").replace(t) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && cp in 32..0x10FFFF) String(Character.toChars(cp)) else m.value
        }
        return t
    }

    private fun ddgHtmlSearch(query: String, maxResults: Int): List<SearchResult> {
        val html = httpGetText("https://html.duckduckgo.com/html/?q=${enc(query)}")
        if (html.isBlank()) return emptyList()
        val anchor = Regex("<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val snip = Regex("class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val hits = anchor.findAll(html).toList()
        val out = ArrayList<SearchResult>()
        for ((i, m) in hits.withIndex()) {
            if (out.size >= maxResults) break
            var href = unescapeHtml(m.groupValues[1])
            if (href.contains("uddg=")) {
                href = try { java.net.URLDecoder.decode(href.substringAfter("uddg=").substringBefore("&"), "UTF-8") } catch (_: Exception) { href }
            } else if (href.startsWith("//")) href = "https:$href"
            if (!href.startsWith("http") || href.contains("duckduckgo.com/y.js")) continue
            val title = unescapeHtml(stripHtml(m.groupValues[2]))
            val endPos = if (i + 1 < hits.size) hits[i + 1].range.first else html.length
            val seg = html.substring(m.range.last, endPos.coerceAtLeast(m.range.last))
            val snippet = snip.find(seg)?.groupValues?.get(1)?.let { unescapeHtml(stripHtml(it)) }.orEmpty()
            val host = try { URL(href).host.removePrefix("www.") } catch (_: Exception) { "web" }
            if (title.isNotBlank()) out.add(SearchResult(title, snippet, href, host))
        }
        return out
    }

    private fun wikiSearch(query: String): List<SearchResult> {
        val results = ArrayList<SearchResult>()
        for (lang in listOf("ru", "en")) {
            try {
                val hits = httpGetJson("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(query)}&format=json&srlimit=2&utf8=1")
                    .optJSONObject("query")?.optJSONArray("search") ?: continue
                for (i in 0 until hits.length()) {
                    val title = hits.optJSONObject(i)?.optString("title").orEmpty()
                    if (title.isBlank()) continue
                    val summary = httpGetJson("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title)}")
                    val extract = summary.optString("extract")
                    if (extract.isBlank()) continue
                    val pageUrl = summary.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")
                        ?: "https://$lang.wikipedia.org/wiki/${enc(title)}"
                    results.add(SearchResult(title, extract, pageUrl, if (lang == "ru") "Wikipedia (RU)" else "Wikipedia (EN)"))
                }
            } catch (_: Exception) {}
        }
        return results
    }

    private fun redditSearch(query: String, maxResults: Int): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            val json = httpGetJson("https://www.reddit.com/search.json?q=${enc(query)}&limit=$maxResults&sort=relevance&raw_json=1")
            val ch = json.optJSONObject("data")?.optJSONArray("children") ?: return out
            for (i in 0 until ch.length()) {
                val d = ch.optJSONObject(i)?.optJSONObject("data") ?: continue
                val title = d.optString("title")
                if (title.isBlank()) continue
                val body = d.optString("selftext").replace(Regex("\\s+"), " ").take(300)
                val meta = "r/${d.optString("subreddit")} · ${d.optInt("score")} pts · ${d.optInt("num_comments")} comments"
                out.add(SearchResult(title, if (body.isBlank()) meta else "$meta — $body",
                    "https://www.reddit.com" + d.optString("permalink"), "Reddit"))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun hackerNewsSearch(query: String, maxResults: Int): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            val hits = httpGetJson("https://hn.algolia.com/api/v1/search?query=${enc(query)}&tags=story&hitsPerPage=$maxResults")
                .optJSONArray("hits") ?: return out
            for (i in 0 until hits.length()) {
                val h = hits.optJSONObject(i) ?: continue
                val title = h.optString("title")
                if (title.isBlank()) continue
                val id = h.optString("objectID")
                val meta = "${h.optInt("points")} pts · ${h.optInt("num_comments")} comments"
                val text = stripHtml(h.optString("story_text")).take(250)
                out.add(SearchResult(title, if (text.isBlank()) meta else "$meta — $text",
                    "https://news.ycombinator.com/item?id=$id", "Hacker News"))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun stackExchangeSearch(query: String, maxResults: Int): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            val items = httpGetJson("https://api.stackexchange.com/2.3/search/excerpts?order=desc&sort=relevance&q=${enc(query)}&site=stackoverflow&pagesize=$maxResults")
                .optJSONArray("items") ?: return out
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val id = obj.optLong("question_id", obj.optLong("answer_id", 0L))
                val title = unescapeHtml(stripHtml(obj.optString("title")))
                if (title.isBlank() || id == 0L) continue
                val excerpt = unescapeHtml(stripHtml(obj.optString("excerpt"))).replace(Regex("\\s+"), " ").take(300)
                out.add(SearchResult(title, "score ${obj.optInt("score")} — $excerpt",
                    "https://stackoverflow.com/q/$id", "StackOverflow"))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun dedupe(list: List<SearchResult>): List<SearchResult> {
        val seen = HashSet<String>()
        return list.filter { r -> seen.add(r.url.ifBlank { r.title }.trimEnd('/').lowercase()) }
    }

    private fun interleave(lists: List<List<SearchResult>>): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        var i = 0
        while (lists.any { i < it.size }) {
            for (l in lists) if (i < l.size) out.add(l[i])
            i++
        }
        return out
    }

    suspend fun searchBySpec(spec: String, maxResults: Int = 8): List<SearchResult> = coroutineScope {
        val s = spec.trim()
        val siteM = Regex("^site:(\\S+)\\s+(.+)$", RegexOption.IGNORE_CASE).find(s)
        val kindM = Regex("^(web|news|forum|forums|wiki)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(s)
        val res: List<SearchResult> = when {
            siteM != null -> {
                val dom = siteM.groupValues[1].trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
                withContext(Dispatchers.IO) { ddgHtmlSearch("site:$dom ${siteM.groupValues[2]}", maxResults) }
            }
            kindM != null && kindM.groupValues[1].lowercase() == "news" -> {
                val q = kindM.groupValues[2]
                val a = async(Dispatchers.IO) { newsSearch(q, 4) }
                val b = async(Dispatchers.IO) { bingNewsSearch(q, 4) }
                interleave(listOf(a.await(), b.await()))
            }
            kindM != null && kindM.groupValues[1].lowercase().startsWith("forum") -> {
                val q = kindM.groupValues[2]
                val jobs = listOf(
                    async(Dispatchers.IO) { redditSearch(q, 4) },
                    async(Dispatchers.IO) { hackerNewsSearch(q, 3) },
                    async(Dispatchers.IO) { stackExchangeSearch(q, 3) },
                    async(Dispatchers.IO) { ddgHtmlSearch("$q forum OR обсуждение OR форум", 5) }
                )
                interleave(jobs.awaitAll())
            }
            kindM != null && kindM.groupValues[1].lowercase() == "wiki" ->
                withContext(Dispatchers.IO) { wikiSearch(kindM.groupValues[2]) }
            else -> {
                val q = kindM?.groupValues?.get(2) ?: s
                val a = async { webSearch(q, maxResults) }
                val b = async(Dispatchers.IO) { ddgHtmlSearch(q, 6) }
                interleave(listOf(b.await(), a.await()))
            }
        }
        dedupe(res).take(maxResults)
    }

    private fun isPublicHost(host: String): Boolean = try {
        java.net.InetAddress.getAllByName(host).none {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress
        } && !host.endsWith(".local") && !host.endsWith(".internal")
    } catch (_: Exception) { false }

    suspend fun fetchPageText(rawUrl: String, maxChars: Int = 6000): String = withContext(Dispatchers.IO) {
        var url = rawUrl.trim().trimEnd('.', ',', ')')
        try {
            var hops = 0
            while (true) {
                val u = URL(url)
                if (u.protocol != "http" && u.protocol != "https") return@withContext "ERROR: only http(s) links can be opened"
                if (!isPublicHost(u.host)) return@withContext "ERROR: this address is not allowed"
                val c = u.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", BROWSER_UA)
                c.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.8,*/*;q=0.5")
                c.setRequestProperty("Accept-Language", "ru,en;q=0.8")
                c.connectTimeout = 8000
                c.readTimeout = 9000
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    c.disconnect()
                    if (loc.isNullOrBlank() || ++hops > 4) return@withContext "ERROR: too many redirects"
                    url = URL(u, loc).toString()
                    continue
                }
                if (code !in 200..299) { c.disconnect(); return@withContext "ERROR: HTTP $code" }
                val type = (c.contentType ?: "").lowercase()
                if (type.isNotEmpty() && !(type.contains("text/") || type.contains("json") || type.contains("xml"))) {
                    c.disconnect(); return@withContext "ERROR: unsupported content type ($type)"
                }
                val bytes = c.inputStream.use { readLimited(it, 700_000) }
                c.disconnect()
                val head = String(bytes, 0, minOf(bytes.size, 3000), Charsets.ISO_8859_1)
                val csName = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                    ?: Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
                val cs = try { if (csName != null) java.nio.charset.Charset.forName(csName) else Charsets.UTF_8 } catch (_: Exception) { Charsets.UTF_8 }
                val html = String(bytes, cs)
                val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(html)?.groupValues?.get(1)?.let { unescapeHtml(stripHtml(it)).trim() }.orEmpty()
                val text = if (type.contains("html") || html.contains("<html", true) || html.contains("<body", true)) htmlToText(html) else html
                val body = text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n\\s*(\\n\\s*)+"), "\n\n").trim()
                return@withContext (if (title.isNotBlank()) "Title: $title\n" else "") + body.take(maxChars)
            }
            ""
        } catch (e: Exception) {
            "ERROR: ${e.message?.take(120) ?: "failed to load"}"
        }
    }

    private fun htmlToText(html: String): String {
        var t = html
        t = Regex("(?is)<(script|style|noscript|svg|template|iframe|head)\\b.*?</\\1\\s*>").replace(t, " ")
        t = Regex("(?s)<!--.*?-->").replace(t, " ")
        t = Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6]|/section|/article|/blockquote)[^>]*>").replace(t, "\n")
        t = Regex("(?i)<li[^>]*>").replace(t, "\n• ")
        t = t.replace(Regex("<[^>]+>"), " ")
        return unescapeHtml(t)
    }

    suspend fun runToolCalls(calls: List<ChatTools.ToolCall>, shortMode: Boolean): String = coroutineScope {
        val maxRes = if (shortMode) 4 else 7
        val parts = calls.map { call ->
            async {
                if (call.kind == "open") {
                    val text = fetchPageText(call.arg, if (shortMode) 2500 else 6000)
                    "### PAGE ${call.arg}\n$text"
                } else {
                    val r = try { searchBySpec(call.arg, maxRes) } catch (e: Exception) { emptyList() }
                    if (r.isEmpty()) "### SEARCH ${call.arg}\n(no results)"
                    else "### SEARCH ${call.arg}\n" + r.joinToString("\n") { x ->
                        "• [${x.source}] ${x.title}" + (if (x.snippet.isNotBlank()) ": ${x.snippet.take(300)}" else "") + "\n  ${x.url}"
                    }
                }
            }
        }
        parts.awaitAll().joinToString("\n\n")
    }
}
