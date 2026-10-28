package com.youraicqa.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class InstalledPlugin(
    val id: String,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "1.0",
    val capability: String = "",
    val permissions: Set<PluginPermission> = emptySet(),
    val capabilities: PluginCapabilities = PluginCapabilities.NONE,
    val script: String = "",
    val sha256: String = "",
    val enabled: Boolean = true
)

object PluginStore {
    private const val PREFS = "nanoai"
    private const val KEY = "installed_plugins"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<InstalledPlugin> {
        return try {
            val arr = JSONArray(prefs(context).getString(KEY, "[]"))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                if (id.isBlank()) return@mapNotNull null
                InstalledPlugin(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    description = o.optString("description"),
                    author = o.optString("author"),
                    version = o.optString("version", "1.0"),
                    capability = o.optString("capability"),
                    permissions = PluginPermission.parseSet(o.optJSONArray("permissions")),
                    capabilities = PluginCapabilities.parse(o.optJSONObject("capabilities")),
                    script = o.optString("script"),
                    sha256 = o.optString("sha256"),
                    enabled = o.optBoolean("enabled", true)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<InstalledPlugin>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("description", p.description)
                .put("author", p.author)
                .put("version", p.version)
                .put("capability", p.capability)
                .put("permissions", JSONArray(p.permissions.map { it.name }))
                .put("capabilities", p.capabilities.toJson())
                .put("script", p.script)
                .put("sha256", p.sha256)
                .put("enabled", p.enabled))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun install(context: Context, plugin: InstalledPlugin) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == plugin.id }
        list.add(plugin)
        save(context, list)
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        save(context, load(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun update(context: Context, id: String, name: String, description: String, author: String) {
        save(context, load(context).map {
            if (it.id == id) it.copy(
                name = name.trim().ifBlank { it.name },
                description = description.trim(),
                author = author.trim()
            ) else it
        })
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun parse(text: String): InstalledPlugin? {
        return try {
            val o = JSONObject(text.trim().removePrefix("\uFEFF"))
            val id = o.optString("id").trim()
            if (id.isBlank()) return null
            val capabilities = PluginCapabilities.parse(o.optJSONObject("capabilities"))
            val declaredPermissions = PluginPermission.parseSet(o.optJSONArray("permissions"))
            InstalledPlugin(
                id = id,
                name = o.optString("name").ifBlank { id },
                description = o.optString("description").ifBlank { o.optString("desc") },
                author = o.optString("author"),
                version = o.optString("version", "1.0"),
                capability = o.optString("capability").ifBlank { o.optString("type") },
                permissions = declaredPermissions + capabilities.impliedPermissions(),
                capabilities = capabilities,
                script = o.optString("script"),
                enabled = o.optBoolean("enabled", true)
            )
        } catch (_: Exception) { null }
    }
}
