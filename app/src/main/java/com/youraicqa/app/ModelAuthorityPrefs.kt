package com.youraicqa.app

import android.content.Context
import org.json.JSONArray

object ModelAuthorityPrefs {
    const val TASK_TEXT = "text"
    const val TASK_CODE = "code"
    const val TASK_IMAGE = "image"
    const val TASK_VISION = "vision"
    val TASKS = listOf(TASK_TEXT, TASK_CODE, TASK_IMAGE, TASK_VISION)

    fun isVisionCapable(m: MainActivity.Model): Boolean {
        val id = m.id.lowercase()
        if (BuiltinModels.isSystemOne(m.provider, m.id)) return false
        return !m.isImage && (m.isVision || m.isVideoVision || m.isMultimodal || isBuiltinVision(m) || id.contains("omni") || id.contains("gemini") || id.contains("vision") ||
            id.contains("-vl") || id.contains("vl-") || m.provider == "gemini")
    }

    fun isVideoCapable(m: MainActivity.Model): Boolean =
        !m.isImage && !m.isLocal && m.pluginId.isBlank() && (m.isVideoVision || m.provider == "gemini")

    private const val KEY_BUILTIN_VISION = "builtin_vision_ids"
    @Volatile private var builtinVision: Set<String> = emptySet()
    @Volatile private var builtinVisionLoaded = false

    fun loadBuiltinModes(c: Context) {
        val raw = c.applicationContext.getSharedPreferences("nanoai", Context.MODE_PRIVATE).getString(KEY_BUILTIN_VISION, null)
        builtinVision = try {
            if (raw == null) emptySet() else JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        } catch (_: Exception) { emptySet() }
        builtinVisionLoaded = true
    }

    fun isBuiltinVision(m: MainActivity.Model): Boolean =
        m.customProviderUrl.isBlank() && !m.isLocal && m.serverId.isBlank() && m.pluginId.isBlank() && !m.isImage && m.id in builtinVision

    fun builtinVisionIds(c: Context): Set<String> { if (!builtinVisionLoaded) loadBuiltinModes(c); return builtinVision }

    fun setBuiltinVision(c: Context, id: String, on: Boolean) {
        if (!builtinVisionLoaded) loadBuiltinModes(c)
        val next = if (on) builtinVision + id else builtinVision - id
        val a = JSONArray(); next.forEach { a.put(it) }
        c.applicationContext.getSharedPreferences("nanoai", Context.MODE_PRIVATE).edit().putString(KEY_BUILTIN_VISION, a.toString()).apply()
        builtinVision = next
    }

    private const val PREFS = "nanoai"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun listKey(task: String) = "authority_models_$task"
    private fun autoKey(task: String) = "authority_auto_$task"

    fun keyOf(m: MainActivity.Model): String =
        m.id + "|" + m.customProviderUrl + "|" + (if (m.isLocal) "L" else "") + "|" + (if (m.badge == "SRV") m.serverId else "") +
            (if (m.pluginId.isNotBlank()) "|P:" + m.pluginId + "/" + m.pluginProviderId else "") +
            (if (m.modeTag.isNotBlank()) "|M:" + m.modeTag else "")

    fun keys(c: Context, task: String): List<String> {
        val raw = prefs(c).getString(listKey(task), null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setKeys(c: Context, task: String, keys: List<String>) {
        val a = JSONArray()
        keys.distinct().forEach { a.put(it) }
        prefs(c).edit().putString(listKey(task), a.toString()).apply()
    }

    fun autoPick(c: Context, task: String): Boolean = prefs(c).getBoolean(autoKey(task), true)
    fun setAutoPick(c: Context, task: String, v: Boolean) { prefs(c).edit().putBoolean(autoKey(task), v).apply() }

    fun availableModels(c: Context): List<MainActivity.Model> {
        if (!builtinVisionLoaded) loadBuiltinModes(c)
        val servers = try { ServerStore.loadEnabled(c) } catch (_: Exception) { emptyList() }
        val replaced = ServerLogic.replacedBy(servers)
        val builtin = BuiltinModels.list.map { b ->
            val srv = replaced[b.id]
            if (srv == null || b.isImage) b
            else b.copy(
                provider = ApiKeyGate.PROVIDER_SERVER,
                serverId = srv.id,
                desc = "Через сервер ${srv.name.ifBlank { srv.baseUrl }}"
            )
        }
        val replacedPairs = builtin.filter { it.serverId.isNotBlank() }.map { it.id to it.serverId }.toSet()
        val out = ArrayList<MainActivity.Model>(builtin)
        for (cm in ModelsStore.load(c)) {
            out.addAll(ModelModes.expand(cm))
        }
        try {
            for (e in ServerLogic.serverModelEntries(servers).filterNot { (it.modelId to it.serverId) in replacedPairs }) {
                out.add(
                    MainActivity.Model(
                        name = e.modelId, provider = ApiKeyGate.PROVIDER_SERVER, id = e.modelId, badge = "SRV",
                        desc = "Модель сервера ${e.serverName}", accent = 0xFF80CBC4.toInt(), serverId = e.serverId
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun modelsForTask(c: Context, task: String): List<MainActivity.Model> =
        availableModels(c).filter { fitsTask(task, it) }

    private fun fitsTask(task: String, m: MainActivity.Model): Boolean = when (task) {
        TASK_IMAGE -> m.isImage
        TASK_VISION -> isVisionCapable(m)
        else -> !m.isImage
    }

    fun chain(
        c: Context,
        task: String,
        all: List<MainActivity.Model>,
        usable: (MainActivity.Model) -> Boolean
    ): List<MainActivity.Model>? {
        val wanted = keys(c, task)
        if (wanted.isEmpty()) return null
        val resolved = wanted.mapNotNull { k ->
            all.firstOrNull { keyOf(it) == k && fitsTask(task, it) }
        }.filter(usable)
        if (resolved.isEmpty()) return null
        return if (autoPick(c, task)) resolved else listOf(resolved.first())
    }

    fun hasCredentials(c: Context, m: MainActivity.Model): Boolean {
        if (m.isLocal || m.serverId.isNotBlank() || m.pluginId.isNotBlank()) return true
        val sp = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (m.customProviderUrl.isNotBlank()) {
            val own = ModelsStore.load(c).firstOrNull { it.id == m.id && it.providerUrl == m.customProviderUrl }?.apiKey.orEmpty()
            return own.isNotBlank() || ProvidersStore.keyForUrl(c, m.customProviderUrl).isNotBlank()
        }
        val key = sp.getString(m.provider, "")?.trim().orEmpty()
        if (key.isBlank() && !ApiKeyGate.keyOptional(m.provider)) return false
        if (m.provider == "cloudflare") return sp.getString("cloudflare_account", "")?.trim().orEmpty().isNotBlank()
        return true
    }
}
