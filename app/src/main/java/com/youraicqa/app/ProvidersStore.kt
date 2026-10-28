package com.youraicqa.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String
)

/** Shared provider credentials for user-owned OpenAI-compatible endpoints. */
object ProvidersStore {
    private const val PREFS = "nanoai"
    private const val KEY = "custom_providers"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<CustomProvider> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val url = o.optString("baseUrl").trim().trimEnd('/')
                    if (url.isBlank()) null else CustomProvider(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = o.optString("name").ifBlank { url },
                        baseUrl = url,
                        apiKey = o.optString("apiKey").trim()
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<CustomProvider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("baseUrl", p.baseUrl).put("apiKey", p.apiKey))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, provider: CustomProvider) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == provider.id || it.baseUrl.equals(provider.baseUrl, true) }
        list.add(provider.copy(baseUrl = provider.baseUrl.trim().trimEnd('/')))
        save(context, list)
    }

    fun remove(context: Context, provider: CustomProvider) {
        save(context, load(context).filterNot { it.id == provider.id })
    }

    fun keyForUrl(context: Context, baseUrl: String): String {
        val normalized = baseUrl.trim().trimEnd('/')
        return load(context).firstOrNull { it.baseUrl.equals(normalized, true) }?.apiKey.orEmpty()
    }
}
