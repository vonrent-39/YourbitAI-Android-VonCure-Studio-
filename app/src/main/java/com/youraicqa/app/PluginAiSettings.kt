package com.youraicqa.app

import android.content.Context

object PluginAiSettings {
    private const val PREFS = "nanoai_plugin_ai"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun k(pluginId: String, providerId: String, field: String) = "$pluginId|$providerId|$field"

    fun load(context: Context, pluginId: String, providerId: String): PluginAiSettingsValue {
        val p = prefs(context)
        return PluginAiSettingsValue(
            baseUrl = p.getString(k(pluginId, providerId, "baseUrl"), "").orEmpty(),
            apiKey = p.getString(k(pluginId, providerId, "apiKey"), "").orEmpty()
        )
    }

    fun save(context: Context, pluginId: String, providerId: String, value: PluginAiSettingsValue) {
        prefs(context).edit()
            .putString(k(pluginId, providerId, "baseUrl"), value.baseUrl.trim())
            .putString(k(pluginId, providerId, "apiKey"), value.apiKey.trim())
            .apply()
    }

    fun isConfirmed(context: Context, pluginId: String, providerId: String): Boolean =
        prefs(context).getBoolean(k(pluginId, providerId, "confirmed"), false)

    fun setConfirmed(context: Context, pluginId: String, providerId: String, confirmed: Boolean) {
        prefs(context).edit().putBoolean(k(pluginId, providerId, "confirmed"), confirmed).apply()
    }

    fun saveProviderCache(context: Context, pluginId: String, providers: List<PluginAiProvider>) {
        val arr = org.json.JSONArray()
        providers.forEach { arr.put(PluginAiLogic.providerToJson(it)) }
        prefs(context).edit().putString("cache|$pluginId", arr.toString()).apply()
    }

    fun loadProviderCache(context: Context, pluginId: String, pluginName: String): List<PluginAiProvider> {
        val raw = prefs(context).getString("cache|$pluginId", null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { PluginAiLogic.parseProvider(pluginId, pluginName, it) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun clearProviderCache(context: Context, pluginId: String) {
        prefs(context).edit().remove("cache|$pluginId").apply()
    }

    fun clearPlugin(context: Context, pluginId: String) {
        val p = prefs(context)
        val e = p.edit()
        p.all.keys.filter { it.startsWith("$pluginId|") }.forEach { e.remove(it) }
        e.remove("cache|$pluginId")
        e.apply()
    }
}
