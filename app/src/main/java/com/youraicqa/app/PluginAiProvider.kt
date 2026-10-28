package com.youraicqa.app

import org.json.JSONArray
import org.json.JSONObject

data class PluginAiModel(val id: String, val name: String, val description: String = "")

data class PluginAiField(val required: Boolean = false, val default: String = "")

data class PluginAiProvider(
    val pluginId: String,
    val pluginName: String,
    val id: String,
    val name: String,
    val description: String = "",
    val models: List<PluginAiModel> = emptyList(),
    val baseUrl: PluginAiField? = null,
    val apiKey: PluginAiField? = null,
    val timeoutMs: Long = PluginAiLogic.DEFAULT_TIMEOUT_MS
) {
    val key: String get() = "$pluginId/$id"
}

data class PluginAiSettingsValue(val baseUrl: String = "", val apiKey: String = "")

class PluginAiException(val error: ServerError, cause: Throwable? = null) : Exception(error.display(), cause)

object PluginAiLogic {
    const val DEFAULT_TIMEOUT_MS = 120_000L
    const val MIN_TIMEOUT_MS = 5_000L
    const val MAX_TIMEOUT_MS = 300_000L
    const val MAX_MODELS = 200
    const val MAX_ERROR_CHARS = 300

    private val ID_REGEX = Regex("^[A-Za-z0-9_.-]{1,64}$")

    fun isValidId(id: String) = ID_REGEX.matches(id)

    fun parseProvider(pluginId: String, pluginName: String, json: JSONObject): PluginAiProvider? {
        val id = json.optString("id").trim()
        if (!isValidId(id)) return null
        return PluginAiProvider(
            pluginId = pluginId,
            pluginName = pluginName,
            id = id,
            name = json.optString("name").trim().ifBlank { id }.take(80),
            description = json.optString("description").trim().take(200),
            models = parseModels(json.optJSONArray("models")),
            baseUrl = parseField(json.optJSONObject("settings"), "baseUrl"),
            apiKey = parseField(json.optJSONObject("settings"), "apiKey"),
            timeoutMs = json.optLong("timeoutMs", DEFAULT_TIMEOUT_MS).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        )
    }

    fun providerToJson(p: PluginAiProvider): JSONObject {
        val settings = JSONObject()
        p.baseUrl?.let { settings.put("baseUrl", JSONObject().put("required", it.required).put("default", it.default)) }
        p.apiKey?.let { settings.put("apiKey", JSONObject().put("required", it.required).put("default", it.default)) }
        val models = JSONArray()
        p.models.forEach { m -> models.put(JSONObject().put("id", m.id).put("name", m.name).put("description", m.description)) }
        return JSONObject()
            .put("id", p.id).put("name", p.name).put("description", p.description)
            .put("models", models).put("settings", settings).put("timeoutMs", p.timeoutMs)
    }

    private fun parseField(settings: JSONObject?, name: String): PluginAiField? {
        if (settings == null || !settings.has(name) || settings.isNull(name)) return null
        return when (val v = settings.opt(name)) {
            is Boolean -> if (v) PluginAiField() else null
            is JSONObject -> PluginAiField(
                required = v.optBoolean("required", false),
                default = v.optString("default").trim().take(500)
            )
            else -> null
        }
    }

    fun parseModels(arr: JSONArray?): List<PluginAiModel> {
        if (arr == null) return emptyList()
        val out = LinkedHashMap<String, PluginAiModel>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_MODELS) break
            val item = arr.opt(i)
            val model = when (item) {
                is String -> item.trim().takeIf { it.isNotEmpty() }?.let { PluginAiModel(it.take(200), it.take(200)) }
                is JSONObject -> {
                    val id = item.optString("id").trim().take(200)
                    if (id.isEmpty()) null
                    else PluginAiModel(
                        id = id,
                        name = item.optString("name").trim().ifBlank { id }.take(120),
                        description = item.optString("description").trim().take(200)
                    )
                }
                else -> null
            }
            if (model != null && model.id !in out) out[model.id] = model
        }
        return out.values.toList()
    }

    fun normalizeBaseUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val url = PluginNetGuard.parseHttpUrl(t) ?: return null
        return url.toString().trimEnd('/')
    }

    fun effectiveSettings(provider: PluginAiProvider, saved: PluginAiSettingsValue): PluginAiSettingsValue =
        PluginAiSettingsValue(
            baseUrl = if (provider.baseUrl == null) "" else saved.baseUrl.ifBlank { provider.baseUrl.default },
            apiKey = if (provider.apiKey == null) "" else saved.apiKey
        )

    fun missingSettingError(provider: PluginAiProvider, effective: PluginAiSettingsValue): ServerError? {
        if (provider.baseUrl?.required == true && effective.baseUrl.isBlank()) {
            return ServerError(null, "Адрес API не задан для провайдера «${provider.name}». Укажите его в Настройки → Плагины → ${provider.pluginName}.")
        }
        if (provider.apiKey?.required == true && effective.apiKey.isBlank()) {
            return ServerError(null, "Нет ключа API для провайдера «${provider.name}». Введите его в Настройки → Плагины → ${provider.pluginName}.")
        }
        return null
    }

    fun contentText(content: Any?): String = when (content) {
        null, JSONObject.NULL -> ""
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    if (isNotEmpty()) append('\n')
                    append(part.optString("text"))
                }
            }
        }
        else -> content.toString()
    }

    fun buildRequest(
        provider: PluginAiProvider,
        model: PluginAiModel,
        msgs: JSONArray,
        maxTokens: Int,
        temperature: Double,
        settings: PluginAiSettingsValue
    ): JSONObject {
        val system = ArrayList<String>()
        val history = JSONArray()
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val text = contentText(m.opt("content"))
            if (role == "system") {
                if (text.isNotBlank()) system.add(text)
            } else if (role == "user" || role == "assistant") {
                history.put(JSONObject().put("role", role).put("content", text))
            }
        }
        return JSONObject()
            .put("providerId", provider.id)
            .put("model", JSONObject().put("id", model.id).put("name", model.name))
            .put("systemPrompt", system.joinToString("\n\n"))
            .put("messages", history)
            .put("params", JSONObject().put("maxTokens", maxTokens).put("temperature", temperature).put("stream", false))
            .put("settings", JSONObject().put("baseUrl", settings.baseUrl).put("apiKey", settings.apiKey))
    }

    fun parseResult(value: Any?): String? {
        val text = when (value) {
            null, JSONObject.NULL -> null
            is String -> value
            is JSONObject -> listOf("text", "content", "reply").firstNotNullOfOrNull { k ->
                (value.opt(k) as? String)?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (s in secrets) if (s.length >= 4) out = out.replace(s, "***")
        return out
    }

    fun mapError(pluginName: String, kind: String, message: String, status: Int, secrets: Collection<String> = emptyList()): ServerError {
        val msg = redact(message.trim(), secrets)
        val low = msg.lowercase()
        when (kind.lowercase()) {
            "timeout" -> return ServerErrors.timeout()
            "connection" -> return ServerErrors.cannotConnect()
            "unauthorized" -> return ServerErrors.unauthorized()
            "rate_limited" -> return ServerErrors.rateLimited()
            "not_found" -> return ServerErrors.notFound()
            "content_filter" -> return ServerErrors.contentFiltered()
            "bad_reply" -> return ServerErrors.badReply(msg)
            "http" -> if (status > 0) return ServerErrors.fromHttp(status, msg)
        }
        return when {
            low.contains("timed out") || low.contains("timeout") -> ServerErrors.timeout()
            low.contains("dns_resolution_failed") || low.contains("unknownhost") || low.contains("unable to resolve") ||
                low.contains("connection refused") || low.contains("request_failed") || low.contains("failed to connect") ->
                ServerErrors.cannotConnect()
            low.contains("blocked_address") ->
                ServerError(null, "Плагин «$pluginName» обратился к локальному или внутреннему адресу — такие адреса заблокированы. Нужен публичный адрес API.")
            low.contains("permission_denied") ->
                ServerError(null, "У плагина «$pluginName» нет разрешения на сетевые запросы.")
            low.contains("invalid_url") || low.contains("domain_not_allowed") || low.contains("invalid_redirect") || low.contains("too_many_redirects") ->
                ServerError(null, "Плагин «$pluginName» не смог открыть адрес API ($msg). Проверьте адрес в настройках плагина.")
            else -> ServerError(null, "Плагин «$pluginName» вернул ошибку: ${msg.ifBlank { "неизвестная ошибка" }.take(MAX_ERROR_CHARS)}")
        }
    }

    fun pluginStopped(pluginName: String) =
        ServerError(null, "Плагин «$pluginName» был остановлен или перезагружен во время генерации. Повторите запрос.")

    fun pluginNotReady(pluginName: String) =
        ServerError(null, "Плагин «$pluginName» не запустился. Проверьте, что он включён, и повторите запрос.")

    fun providerGone(modelName: String) =
        ServerError(null, "Плагин, которому принадлежит модель «$modelName», выключен или удалён. Выберите другую модель.")
}
