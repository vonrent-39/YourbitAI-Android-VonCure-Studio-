package com.youraicqa.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

object PluginManager {
    private var runtimes: MutableMap<String, PluginRuntime> = mutableMapOf()
    private var loadedForContext: Context? = null
    private val pendingRuntimeErrors = java.util.ArrayDeque<String>()

    /** Runtime JS errors are queued for the chat UI, while also being preserved in diagnostics. */
    @Synchronized
    fun reportRuntimeError(context: Context, pluginId: String, message: String) {
        val safe = message.replace(Regex("\\s+"), " ").trim().take(180).ifBlank { "Unknown plugin runtime error" }
        NanoLog.log(context, "PLUGIN runtime error id=$pluginId message=$safe")
        val row = "$pluginId: $safe"
        if (pendingRuntimeErrors.lastOrNull() != row) pendingRuntimeErrors.addLast(row)
        while (pendingRuntimeErrors.size > 8) pendingRuntimeErrors.removeFirst()
    }

    @Synchronized
    fun consumeRuntimeError(): String? = if (pendingRuntimeErrors.isEmpty()) null else pendingRuntimeErrors.removeFirst()

    fun reload(context: Context) {
        require(Looper.myLooper() == Looper.getMainLooper()) { "PluginManager.reload must run on main thread" }
        runtimes.values.forEach { it.destroy() }
        runtimes = mutableMapOf()
        loadedForContext = context.applicationContext
        PluginStore.load(context).forEach { plugin ->
            if (!plugin.enabled || plugin.script.isBlank() || plugin.permissions.isEmpty()) return@forEach
            val rt = PluginRuntime(context.applicationContext, plugin)
            rt.start()
            runtimes[plugin.id] = rt
        }
    }

    private fun ensureLoaded(context: Context) {
        if (loadedForContext == null) reload(context)
    }

    suspend fun applyMessageHooks(context: Context, text: String): String {
        ensureLoaded(context)
        var current = text
        for ((pluginId, rt) in runtimes) {
            try {
                rt.awaitReady()
                current = rt.applyMessageHook(current)
            } catch (e: Exception) {
                reportRuntimeError(context, pluginId, e.message ?: e.javaClass.simpleName)
            }
        }
        return current
    }

    fun activeQuickActions(context: Context): List<Pair<String, String>> {
        ensureLoaded(context)
        return runtimes.values.flatMap { it.quickActions }
    }

    suspend fun activeThemeOverride(context: Context): org.json.JSONObject? {
        ensureLoaded(context)
        for (rt in runtimes.values) {
            try {
                rt.awaitReady()
            } catch (_: Exception) {
                continue
            }
            rt.themeOverride?.let { return it }
        }
        return null
    }

    fun activeSearchSources(context: Context): List<String> {
        ensureLoaded(context)
        return runtimes.values.mapNotNull { it.searchSourceName }
    }

    suspend fun applyRenderHooks(context: Context, text: String): String {
        ensureLoaded(context)
        var current = text
        for ((pluginId, rt) in runtimes) {
            try {
                rt.awaitReady()
                current = rt.applyRenderHook(current)
            } catch (e: Exception) {
                reportRuntimeError(context, pluginId, e.message ?: e.javaClass.simpleName)
            }
        }
        return current
    }

    fun activePanels(context: Context): Map<String, String> {
        ensureLoaded(context)
        return runtimes.entries.mapNotNull { (id, rt) -> rt.panelHtml?.let { id to it } }.toMap()
    }

    fun activeUiActions(context: Context): List<Triple<String, String, String>> {
        ensureLoaded(context)
        return runtimes.entries.flatMap { (pluginId, rt) -> rt.uiActions.map { (id, title) -> Triple(pluginId, id, title) } }
    }

    fun activeUiExtensions(context: Context, slot: String): List<UiExtension> {
        ensureLoaded(context)
        return runtimes.values.flatMap { it.uiExtensions }.filter { it.slot == slot }
    }

    fun invokeUiAction(context: Context, pluginId: String, actionId: String, actionContext: org.json.JSONObject? = null) {
        require(Looper.myLooper() == Looper.getMainLooper()) { "PluginManager.invokeUiAction must run on main thread" }
        ensureLoaded(context)
        runtimes[pluginId]?.invokeUiAction(actionId, actionContext)
    }

    @Volatile var aiProvidersListener: (() -> Unit)? = null

    fun notifyAiProvidersChanged() {
        Handler(Looper.getMainLooper()).post {
            val ctx = loadedForContext
            if (ctx != null) {
                runtimes.forEach { (id, rt) ->
                    val live = rt.aiProviders()
                    if (live.isNotEmpty()) PluginAiSettings.saveProviderCache(ctx, id, live)
                }
            }
            aiProvidersListener?.invoke()
        }
    }

    fun activeAiProviders(context: Context): List<PluginAiProvider> {
        ensureLoaded(context)
        val out = mutableListOf<PluginAiProvider>()
        for ((id, rt) in runtimes) {
            val live = rt.aiProviders()
            if (live.isNotEmpty()) { out += live; continue }
            val plugin = PluginStore.load(context).firstOrNull { it.id == id } ?: continue
            if (PluginPermission.AI_PROVIDER !in plugin.permissions) continue
            out += PluginAiSettings.loadProviderCache(context, id, plugin.name)
        }
        return out
    }

    suspend fun awaitAiProviders(context: Context, pluginId: String): List<PluginAiProvider> {
        ensureLoaded(context)
        val rt = runtimes[pluginId] ?: return emptyList()
        if (withTimeoutOrNull(AI_READY_TIMEOUT_MS) { rt.awaitReady(); true } == null) return emptyList()
        return rt.aiProviders()
    }

    private const val AI_READY_TIMEOUT_MS = 10_000L
    const val AI_DEFAULT_TEMPERATURE = 0.7

    suspend fun generateWithAiProvider(
        context: Context,
        pluginId: String,
        providerId: String,
        modelId: String,
        msgs: JSONArray,
        maxTokens: Int,
        temperature: Double = AI_DEFAULT_TEMPERATURE,
        onPartial: (String) -> Unit = {}
    ): String {
        ensureLoaded(context)
        val rt = runtimes[pluginId] ?: throw PluginAiException(PluginAiLogic.providerGone(modelId))
        val name = PluginStore.load(context).firstOrNull { it.id == pluginId }?.name ?: pluginId
        if (withTimeoutOrNull(AI_READY_TIMEOUT_MS) { rt.awaitReady(); true } == null) {
            throw PluginAiException(PluginAiLogic.pluginNotReady(name))
        }
        val provider = rt.aiProviders().firstOrNull { it.id == providerId }
            ?: throw PluginAiException(PluginAiLogic.providerGone(modelId))
        val model = provider.models.firstOrNull { it.id == modelId } ?: PluginAiModel(modelId, modelId)

        val effective = PluginAiLogic.effectiveSettings(provider, PluginAiSettings.load(context, pluginId, providerId))
        PluginAiLogic.missingSettingError(provider, effective)?.let { throw PluginAiException(it) }
        val baseUrl = PluginAiLogic.normalizeBaseUrl(effective.baseUrl)
            ?: throw PluginAiException(
                ServerError(null, "Адрес API для провайдера «${provider.name}» некорректен: нужен адрес вида https://… Исправь его в Настройки → Плагины → ${provider.pluginName}.")
            )
        val settings = PluginAiSettingsValue(baseUrl, effective.apiKey)
        val request = PluginAiLogic.buildRequest(provider, model, msgs, maxTokens, temperature, settings)
        return rt.generate(
            providerId, request, provider.timeoutMs,
            secrets = listOfNotNull(settings.apiKey.takeIf { it.isNotBlank() }),
            onPartial = onPartial
        )
    }

    fun shutdown() {
        Handler(Looper.getMainLooper()).post {
            runtimes.values.forEach { it.destroy() }
            runtimes.clear()
            loadedForContext = null
        }
    }
}
