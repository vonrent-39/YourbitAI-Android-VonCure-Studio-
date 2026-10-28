package com.youraicqa.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class PluginRuntime(private val context: Context, private val plugin: InstalledPlugin) {

    private var webView: WebView? = null
    var themeOverride: JSONObject? = null; private set
    val quickActions = mutableListOf<Pair<String, String>>()
    var searchSourceName: String? = null; private set
    var panelHtml: String? = null; private set
    private val settingsPrefixHolder = "plugin_kv_${plugin.id}"
    private val ready = CompletableDeferred<Unit>()

    val uiActions = mutableListOf<Pair<String, String>>()
    val uiExtensions = java.util.concurrent.CopyOnWriteArrayList<UiExtension>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskRegistry = PluginTaskRegistry()
    private val httpConnector: PluginHttpConnector by lazy { DefaultPluginHttpConnector() }
    private val cancellableJobs = ConcurrentHashMap<String, Job>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private sealed class AiOutcome {
        data class Ok(val value: Any?) : AiOutcome()
        data class Fail(val kind: String, val message: String, val status: Int) : AiOutcome()
    }
    private class PendingGeneration(
        val deferred: CompletableDeferred<AiOutcome>,
        val acc: StringBuilder,
        val onPartial: (String) -> Unit
    )
    private val aiProviderMap = java.util.Collections.synchronizedMap(LinkedHashMap<String, PluginAiProvider>())
    private val pendingGenerations = ConcurrentHashMap<String, PendingGeneration>()
    private val aiNetJobs = ConcurrentHashMap<String, MutableList<Job>>()

    fun aiProviders(): List<PluginAiProvider> = synchronized(aiProviderMap) { aiProviderMap.values.toList() }

    private val APP_SETTINGS_ALLOWLIST = setOf(
        "chat_bubble_style", "haptics_enabled",
        "font_scale", "compact_mode", "animations_enabled", "legacy_gradient_bg", "persona_text"
    )

    private val APP_SETTING_REAL_KEY: Map<String, Pair<String, Char>> = mapOf(
        "font_scale" to ("ui_font_scale" to 'f'),
        "compact_mode" to ("ui_compact" to 'b'),
        "animations_enabled" to ("ui_anim" to 'b'),
        "legacy_gradient_bg" to ("ui_legacy_gradient_bg" to 'b'),
        "persona_text" to ("ui_custom_persona" to 's')
    )

    private val MAX_AI_PARTIAL_CHARS = 2_000_000

    private fun has(p: PluginPermission) = p in plugin.permissions

    fun start() {
        val wv = WebView(context)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = false
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.allowUniversalAccessFromFileURLs = false
        wv.settings.allowFileAccessFromFileURLs = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val bootstrap = buildString {
                    append("window.nano = {\n")
                    append("  setTheme: function(obj){ nanoBridge.setTheme(JSON.stringify(obj)); },\n")
                    append("  addQuickAction: function(id, title){ nanoBridge.addQuickAction(String(id), String(title)); },\n")
                    append("  onMessage: function(fn){ window.__onMessage = fn; },\n")
                    append("  onRender: function(fn){ window.__onRender = fn; },\n")
                    append("  registerSearchSource: function(name){ nanoBridge.registerSearchSource(String(name)); },\n")
                    append("  setPanelHtml: function(html){ nanoBridge.setPanelHtml(String(html)); },\n")
                    append("  setAppSetting: function(key, value){ nanoBridge.setAppSetting(String(key), String(value)); },\n")
                    append("  getSetting: function(key){ return nanoBridge.getSetting(String(key)); },\n")
                    append("  setSetting: function(key, value){ nanoBridge.setSetting(String(key), String(value)); }\n")
                    append("};\n")
                    append("window.__nanoCbSeq = 0;\n")
                    append("window.__nanoCallbacks = {};\n")
                    append("window.__nanoResolve = function(id, ok, resultJson){\n")
                    append("  var cb = window.__nanoCallbacks[id]; if (!cb) return; delete window.__nanoCallbacks[id];\n")
                    append("  var val; try { val = resultJson ? JSON.parse(resultJson) : undefined; } catch(e){ val = resultJson; }\n")
                    append("  if (ok) cb.resolve(val); else cb.reject(val);\n")
                    append("};\n")
                    append("function __nanoCall(method, args){\n")
                    append("  return new Promise(function(resolve, reject){\n")
                    append("    var id = ++window.__nanoCbSeq;\n")
                    append("    window.__nanoCallbacks[id] = { resolve: resolve, reject: reject };\n")
                    append("    try { nanoBridge[method](id, JSON.stringify(args||{})); }\n")
                    append("    catch(e){ delete window.__nanoCallbacks[id]; reject(String(e)); }\n")
                    append("  });\n")
                    append("}\n")
                    append("window.nano.net = { request: function(opts){\n")
                    append("  return __nanoCall('netRequest', opts).then(function(res){\n")
                    append("    if (opts && opts.responseType === 'json' && typeof res.body === 'string') {\n")
                    append("      try { res.body = JSON.parse(res.body); } catch(e) {}\n")
                    append("    }\n")
                    append("    return res;\n")
                    append("  });\n")
                    append("} };\n")
                    append("window.nano.download = { start: function(opts){ return __nanoCall('downloadStart', opts); } };\n")
                    append("window.nano.files = { save: function(opts){ return __nanoCall('filesSave', opts); } };\n")
                    append("window.nano.task = {\n")
                    append("  start: function(opts){ return __nanoCall('taskStart', opts); },\n")
                    append("  progress: function(id, pct){ try { nanoBridge.taskProgress(String(id), (pct|0)); } catch(e){} },\n")
                    append("  complete: function(id){ try { nanoBridge.taskComplete(String(id)); } catch(e){} },\n")
                    append("  fail: function(id, msg){ try { nanoBridge.taskFail(String(id), String(msg||'')); } catch(e){} },\n")
                    append("  cancel: function(id){ try { nanoBridge.taskCancel(String(id)); } catch(e){} },\n")
                    append("  isCancelled: function(id){ try { return !!nanoBridge.taskIsCancelled(String(id)); } catch(e){ return false; } },\n")
                    append("  status: function(id){ try { var s = nanoBridge.taskStatus(String(id)); return s ? JSON.parse(s) : null; } catch(e){ return null; } },\n")
                    append("  result: function(id){ try { var s = nanoBridge.taskResult(String(id)); return s ? JSON.parse(s) : null; } catch(e){ return null; } }\n")
                    append("};\n")
                    append("window.nano.ui = {\n")
                    append("  action: { register: function(opts){\n")
                    append("    opts = opts || {};\n")
                    append("    window.__nanoActions = window.__nanoActions || {};\n")
                    append("    window.__nanoActions[String(opts.id)] = opts.handler;\n")
                    append("    try { nanoBridge.registerUiAction(String(opts.id), String(opts.title||opts.id)); } catch(e){}\n")
                    append("  } },\n")
                    append("  notify: function(opts){ try { nanoBridge.notify(JSON.stringify(opts||{})); } catch(e){} },\n")
                    append("  extend: function(opts){\n")
                    append("    opts = opts || {};\n")
                    append("    window.__nanoActions = window.__nanoActions || {};\n")
                    append("    if (typeof opts.handler === 'function') window.__nanoActions[String(opts.id)] = opts.handler;\n")
                    append("    try { return !!nanoBridge.extendUi(JSON.stringify({ slot: opts.slot, id: opts.id, title: opts.title, icon: opts.icon, panel: opts.panel, prompt: opts.prompt })); } catch(e){ return false; }\n")
                    append("  }\n")
                    append("};\n")
                    append("// --- nano.ai: свои AI-провайдеры (AI_PROVIDER) ---\n")
                    append("window.__nanoAiHandlers = {};\n")
                    append("window.__nanoAiActive = {};\n")
                    append("window.nano.ai = {\n")
                    append("  registerProvider: function(def){\n")
                    append("    def = def || {};\n")
                    append("    if (typeof def.generate !== 'function') throw new Error('nano.ai.registerProvider: generate must be a function');\n")
                    append("    var meta = { id: String(def.id || ''), name: def.name, description: def.description, models: def.models, settings: def.settings, timeoutMs: def.timeoutMs };\n")
                    append("    var id = '';\n")
                    append("    try { id = nanoBridge.registerAiProvider(JSON.stringify(meta)); } catch(e){}\n")
                    append("    if (!id) throw new Error('nano.ai.registerProvider: invalid id or AI_PROVIDER permission missing');\n")
                    append("    window.__nanoAiHandlers[id] = def.generate;\n")
                    append("    return id;\n")
                    append("  },\n")
                    append("  setModels: function(providerId, models){ try { nanoBridge.setAiModels(String(providerId), JSON.stringify(models || [])); } catch(e){} },\n")
                    append("  fail: function(kind, message, status){ var e = new Error(String(message || kind || 'error')); e.kind = String(kind || ''); e.status = (status | 0); return e; }\n")
                    append("};\n")
                    append("window.__nanoAiGenerate = function(reqId, providerId, reqJson){\n")
                    append("  var finished = false, cancelled = false, cbs = [];\n")
                    append("  function send(ok, payload){\n")
                    append("    if (finished) return;\n")
                    append("    finished = true;\n")
                    append("    delete window.__nanoAiActive[reqId];\n")
                    append("    var text;\n")
                    append("    try { text = JSON.stringify(payload); } catch(e){ ok = false; text = JSON.stringify({ kind: 'bad_reply', message: 'result is not serializable', status: 0 }); }\n")
                    append("    try { nanoBridge.aiDone(reqId, ok, text); } catch(e){}\n")
                    append("  }\n")
                    append("  function fail(e){\n")
                    append("    var o = { kind: '', message: '', status: 0 };\n")
                    append("    if (typeof e === 'string') { o.message = e; }\n")
                    append("    else if (e && typeof e === 'object') {\n")
                    append("      o.kind = String(e.kind || e.code || '');\n")
                    append("      o.message = String(e.message || e.error || '');\n")
                    append("      o.status = (e.status | 0) || 0;\n")
                    append("      if (!o.message && !o.kind) { try { o.message = JSON.stringify(e); } catch(x){} }\n")
                    append("    } else if (e != null) { o.message = String(e); }\n")
                    append("    send(false, o);\n")
                    append("  }\n")
                    append("  var h = window.__nanoAiHandlers[providerId];\n")
                    append("  if (typeof h !== 'function') { fail('provider handler not found'); return; }\n")
                    append("  var req;\n")
                    append("  try { req = JSON.parse(reqJson); } catch(e){ fail('invalid request'); return; }\n")
                    append("  req.id = reqId;\n")
                    append("  req.isCancelled = function(){ return cancelled; };\n")
                    append("  req.onCancel = function(fn){ if (typeof fn !== 'function') return; if (cancelled) { try { fn(); } catch(e){} } else { cbs.push(fn); } };\n")
                    append("  req.emit = function(t){ if (!finished && !cancelled) { try { nanoBridge.aiPartial(reqId, String(t)); } catch(e){} } };\n")
                    append("  req.request = function(opts){ var o = {}; opts = opts || {}; for (var k in opts) { o[k] = opts[k]; } o.aiRequestId = reqId; return window.nano.net.request(o); };\n")
                    append("  window.__nanoAiActive[reqId] = { cancel: function(){\n")
                    append("    cancelled = true; var l = cbs.slice(); cbs = [];\n")
                    append("    for (var i = 0; i < l.length; i++) { try { l[i](); } catch(e){} }\n")
                    append("    delete window.__nanoAiActive[reqId];\n")
                    append("  } };\n")
                    append("  try { Promise.resolve(h(req)).then(function(v){ send(true, { value: (v === undefined ? null : v) }); }, fail); }\n")
                    append("  catch(e){ fail(e); }\n")
                    append("};\n")
                    append("window.__nanoAiCancel = function(reqId){ var a = window.__nanoAiActive[reqId]; if (a) a.cancel(); };\n")
                    append("window.__nanoInvokeAction = function(id, contextJson){\n")
                    append("  var fn = window.__nanoActions && window.__nanoActions[id];\n")
                    append("  if (typeof fn !== 'function') return;\n")
                    append("  var ctx = {}; try { ctx = contextJson ? JSON.parse(contextJson) : {}; } catch(e){}\n")
                    append("  try { var r = fn(ctx); if (r && typeof r.catch === 'function') r.catch(function(){}); } catch(e){}\n")
                    append("};\n")
                    append("window.onerror = function(message, source, line, column, error){ try { nanoBridge.pluginError(String(message || (error && error.message) || 'JavaScript error')); } catch(e){} return true; };\n")
                    append("window.onunhandledrejection = function(event){ try { nanoBridge.pluginError(String(event && event.reason && (event.reason.message || event.reason) || 'Unhandled promise rejection')); } catch(e){} };\n")
                    append("try {\n")
                    append(plugin.script)
                    append("\n} catch(e) { try { nanoBridge.pluginError(String(e && e.message || e)); } catch(_){} }\n")
                }
                view.evaluateJavascript(bootstrap) { ready.complete(Unit) }
            }
        }
        wv.addJavascriptInterface(Bridge(), "nanoBridge")
        webView = wv
        wv.loadDataWithBaseURL("about:blank", "<html><body></body></html>", "text/html", "utf-8", null)
    }

    suspend fun awaitReady() = ready.await()

    val isReady: Boolean get() = ready.isCompleted

    fun destroy() {
        pendingGenerations.values.forEach {
            it.deferred.completeExceptionally(PluginAiException(PluginAiLogic.pluginStopped(plugin.name)))
        }
        pendingGenerations.clear()
        aiNetJobs.clear()
        ioScope.cancel()
        cancellableJobs.clear()
        taskRegistry.clear()
        webView?.destroy()
        webView = null
    }

    fun invokeUiAction(id: String, context: JSONObject?) {
        if (!has(PluginPermission.UI_ACTION) && !has(PluginPermission.UI_EXTEND)) return
        val wv = webView ?: return
        val idJs = JSONObject.quote(id)
        val ctxJs = JSONObject.quote(context?.toString() ?: "{}")
        mainHandler.post { wv.evaluateJavascript("window.__nanoInvokeAction($idJs, $ctxJs);", null) }
    }

    suspend fun applyMessageHook(text: String): String {
        if (!has(PluginPermission.MESSAGE_HOOKS)) return text
        val wv = webView ?: return text
        val escaped = JSONObject.quote(text)
        val js = "(function(){ if(typeof window.__onMessage==='function'){ try { return String(window.__onMessage($escaped)); } catch(e){ return $escaped; } } return $escaped; })();"
        return suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result ->
                val unquoted = try {
                    JSONObject("{\"v\":$result}").getString("v")
                } catch (_: Exception) { text }
                if (cont.isActive) cont.resume(unquoted)
            }
        }
    }

    suspend fun applyRenderHook(text: String): String {
        if (!has(PluginPermission.MESSAGE_RENDER)) return text
        val wv = webView ?: return text
        val escaped = JSONObject.quote(text)
        val js = "(function(){ if(typeof window.__onRender==='function'){ try { return String(window.__onRender($escaped)); } catch(e){ return $escaped; } } return $escaped; })();"
        return suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(js) { result ->
                val unquoted = try { JSONObject("{\"v\":$result}").getString("v") } catch (_: Exception) { text }
                if (cont.isActive) cont.resume(unquoted)
            }
        }
    }

    suspend fun generate(
        providerId: String,
        request: JSONObject,
        timeoutMs: Long,
        secrets: List<String>,
        onPartial: (String) -> Unit
    ): String {
        if (!has(PluginPermission.AI_PROVIDER)) {
            throw PluginAiException(ServerError(null, "У плагина «${plugin.name}» нет разрешения на AI-провайдеры."))
        }
        val wv = webView ?: throw PluginAiException(PluginAiLogic.pluginStopped(plugin.name))
        val reqId = java.util.UUID.randomUUID().toString()
        val pending = PendingGeneration(CompletableDeferred(), StringBuilder(), onPartial)
        pendingGenerations[reqId] = pending
        val js = "window.__nanoAiGenerate(${JSONObject.quote(reqId)}, ${JSONObject.quote(providerId)}, ${JSONObject.quote(request.toString())});"
        mainHandler.post {
            try { wv.evaluateJavascript(js, null) } catch (e: Exception) {
                pending.deferred.complete(AiOutcome.Fail("", "webview_error: ${e.message}", 0))
            }
        }
        val outcome = try {
            withTimeoutOrNull(timeoutMs) { pending.deferred.await() }
        } catch (e: CancellationException) {
            cancelGeneration(reqId)
            throw e
        } finally {
            pendingGenerations.remove(reqId)
        }
        if (outcome == null) {
            cancelGeneration(reqId)
            throw PluginAiException(ServerErrors.timeout())
        }
        aiNetJobs.remove(reqId)
        return when (outcome) {
            is AiOutcome.Ok -> PluginAiLogic.parseResult(outcome.value)
                ?: pending.acc.toString().takeIf { it.isNotBlank() }
                ?: throw PluginAiException(ServerError(null, "Плагин «${plugin.name}» вернул пустой ответ."))
            is AiOutcome.Fail -> throw PluginAiException(
                PluginAiLogic.mapError(plugin.name, outcome.kind, outcome.message, outcome.status, secrets)
            )
        }
    }

    private fun cancelGeneration(reqId: String) {
        aiNetJobs.remove(reqId)?.toList()?.forEach { it.cancel() }
        val wv = webView ?: return
        mainHandler.post {
            try { wv.evaluateJavascript("window.__nanoAiCancel(${JSONObject.quote(reqId)});", null) } catch (_: Exception) {}
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun pluginError(message: String) {
            PluginManager.reportRuntimeError(context, plugin.id, message)
        }

        @JavascriptInterface
        fun setTheme(json: String) {
            if (!has(PluginPermission.UI_THEME)) return
            themeOverride = try { JSONObject(json) } catch (_: Exception) { null }
        }

        @JavascriptInterface
        fun addQuickAction(id: String, title: String) {
            if (!has(PluginPermission.QUICK_ACTIONS)) return
            quickActions.add(id to title)
        }

        @JavascriptInterface
        fun registerSearchSource(name: String) {
            if (!has(PluginPermission.SEARCH_SOURCE)) return
            searchSourceName = name
        }

        @JavascriptInterface
        fun setPanelHtml(html: String) {
            if (!has(PluginPermission.CUSTOM_PANEL)) return
            panelHtml = html
        }

        @JavascriptInterface
        fun setAppSetting(key: String, value: String) {
            if (!has(PluginPermission.APP_SETTINGS)) return
            if (key !in APP_SETTINGS_ALLOWLIST) return
            val real = APP_SETTING_REAL_KEY[key]
            if (real == null) {
                context.getSharedPreferences("nanoai_plugin_app_settings", Context.MODE_PRIVATE)
                    .edit().putString(key, value).apply()
                return
            }
            val (realKey, type) = real
            val e = context.getSharedPreferences("nanoai", Context.MODE_PRIVATE).edit()
            when (type) {
                'b' -> value.toBooleanStrictOrNull()?.let { e.putBoolean(realKey, it) } ?: return
                'f' -> value.toFloatOrNull()?.coerceIn(0.5f, 2f)?.let { e.putFloat(realKey, it) } ?: return
                else -> e.putString(realKey, value.take(4000))
            }
            e.apply()
        }

        @JavascriptInterface
        fun getSetting(key: String): String {
            if (!has(PluginPermission.SETTINGS)) return ""
            val p = context.getSharedPreferences(settingsPrefixHolder, Context.MODE_PRIVATE)
            return p.getString(key, "") ?: ""
        }

        @JavascriptInterface
        fun sysPromptStatus(): String {
            if (!has(PluginPermission.SETTINGS)) return ""
            val p = context.getSharedPreferences("nanoai", Context.MODE_PRIVATE)
            val disabled = p.getBoolean("eco_disable_system_prompt", false)
            val customEnabled = p.getBoolean("dev_custom_system_prompt_enabled", false)
            val customHasText = p.getString("dev_custom_system_prompt", "").orEmpty().isNotBlank()
            return JSONObject()
                .put("disabled", disabled)
                .put("customActive", customEnabled && customHasText)
                .toString()
        }

        @JavascriptInterface
        fun setSetting(key: String, value: String) {
            if (!has(PluginPermission.SETTINGS)) return
            val p = context.getSharedPreferences(settingsPrefixHolder, Context.MODE_PRIVATE)
            p.edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun netRequest(callbackId: Int, argsJson: String) {
            if (!has(PluginPermission.NETWORK)) return resolveJs(callbackId, false, err("permission_denied"))
            val args = try { JSONObject(argsJson) } catch (_: Exception) { null }
                ?: return resolveJs(callbackId, false, err("invalid_args"))
            val url = args.optString("url")
            val method = args.optString("method", "GET")
            val responseType = args.optString("responseType", "text")
            val timeout = if (args.has("timeout")) args.optInt("timeout", PluginNetCapability.DEFAULT_TIMEOUT_MS) else PluginNetCapability.DEFAULT_TIMEOUT_MS
            val headers = mutableMapOf<String, String>()
            args.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
            val body = if (args.has("body")) args.optString("body").toByteArray(Charsets.UTF_8) else null

            val aiRequestId = args.optString("aiRequestId")
            val netJob = ioScope.launch {
                val result = PluginNetCapability.request(
                    httpConnector, plugin.capabilities.networkDomains.ifEmpty { setOf("*") }, url, method, headers, timeout, body
                )
                if (!isActive) return@launch
                when (result) {
                    is PluginNetResult.Success -> resolveJs(callbackId, true, PluginNetCapability.successToJson(result, responseType).toString())
                    is PluginNetResult.Error -> resolveJs(callbackId, false, err(result.reason))
                }
            }
            if (aiRequestId.isNotEmpty() && pendingGenerations.containsKey(aiRequestId)) {
                val jobs = aiNetJobs.getOrPut(aiRequestId) { java.util.Collections.synchronizedList(mutableListOf<Job>()) }
                jobs.add(netJob)
                netJob.invokeOnCompletion { jobs.remove(netJob) }
            }
        }

        @JavascriptInterface
        fun downloadStart(callbackId: Int, argsJson: String) {
            if (!has(PluginPermission.DOWNLOAD)) return resolveJs(callbackId, false, err("permission_denied"))
            val args = try { JSONObject(argsJson) } catch (_: Exception) { null }
                ?: return resolveJs(callbackId, false, err("invalid_args"))
            val url = args.optString("url")
            val filename = args.optString("filename")
            if (filename.isBlank()) return resolveJs(callbackId, false, err("missing_filename"))

            val task = taskRegistry.create("download:$filename")
            resolveJs(callbackId, true, task.toJson().toString())

            ioScope.launch {
                val outcome = PluginDownloadCapability.run(
                    context = context,
                    connector = httpConnector,
                    allowlist = plugin.capabilities.downloadDomains.ifEmpty { setOf("*") },
                    pluginId = plugin.id,
                    rawUrl = url,
                    filename = filename,
                    isCancelled = { taskRegistry.isCancelled(task.id) },
                    onProgress = { pct -> taskRegistry.progress(task.id, pct) }
                )
                taskRegistry.setResult(task.id, PluginDownloadCapability.outcomeToJson(outcome, filename).toString())
                if (outcome.ok) taskRegistry.complete(task.id) else taskRegistry.fail(task.id, outcome.reason)
            }
        }

        @JavascriptInterface
        fun filesSave(callbackId: Int, argsJson: String) {
            if (!has(PluginPermission.FILES)) return resolveJs(callbackId, false, err("permission_denied"))
            val args = try { JSONObject(argsJson) } catch (_: Exception) { null }
                ?: return resolveJs(callbackId, false, err("invalid_args"))
            val filename = args.optString("filename")
            val data = args.optString("data")
            val encoding = args.optString("encoding", "utf8")
            ioScope.launch {
                val result = PluginFiles.save(context, plugin.id, filename, data, encoding)
                val json = JSONObject().put("ok", result.ok).put("bytesWritten", result.bytesWritten).put("error", result.error)
                resolveJs(callbackId, result.ok, json.toString())
            }
        }

        @JavascriptInterface
        fun taskStart(callbackId: Int, argsJson: String) {
            if (!has(PluginPermission.BACKGROUND_TASK)) return resolveJs(callbackId, false, err("permission_denied"))
            val name = try { JSONObject(argsJson).optString("name", "task") } catch (_: Exception) { "task" }
            val task = taskRegistry.create(name)
            resolveJs(callbackId, true, task.toJson().toString())
        }

        private fun hasTaskAccess() =
            has(PluginPermission.BACKGROUND_TASK) || has(PluginPermission.DOWNLOAD)

        @JavascriptInterface
        fun taskProgress(id: String, percent: Int) {
            if (!hasTaskAccess()) return
            taskRegistry.progress(id, percent)
        }

        @JavascriptInterface
        fun taskComplete(id: String) {
            if (!hasTaskAccess()) return
            taskRegistry.complete(id)
        }

        @JavascriptInterface
        fun taskFail(id: String, message: String) {
            if (!hasTaskAccess()) return
            taskRegistry.fail(id, message)
        }

        @JavascriptInterface
        fun taskCancel(id: String) {
            if (!hasTaskAccess()) return
            taskRegistry.cancel(id)
            cancellableJobs.remove(id)?.cancel()
        }

        @JavascriptInterface
        fun taskIsCancelled(id: String): Boolean {
            if (!hasTaskAccess()) return false
            return taskRegistry.isCancelled(id)
        }

        @JavascriptInterface
        fun taskStatus(id: String): String {
            if (!hasTaskAccess()) return ""
            return taskRegistry.get(id)?.toJson()?.toString() ?: ""
        }

        @JavascriptInterface
        fun taskResult(id: String): String {
            if (!hasTaskAccess()) return ""
            return taskRegistry.get(id)?.result ?: ""
        }

        @JavascriptInterface
        fun registerUiAction(id: String, title: String) {
            if (!has(PluginPermission.UI_ACTION)) return
            if (id.isBlank()) return
            uiActions.removeAll { it.first == id }
            uiActions.add(id to title.ifBlank { id })
        }

        @JavascriptInterface
        fun extendUi(json: String): Boolean {
            if (!has(PluginPermission.UI_EXTEND)) return false
            val ext = try { UiExtension.parse(plugin.id, JSONObject(json)) } catch (_: Exception) { null } ?: return false
            uiExtensions.removeAll { it.slot == ext.slot && it.id == ext.id }
            if (uiExtensions.size >= UiExtension.MAX_PER_PLUGIN) return false
            uiExtensions.add(ext)
            return true
        }

        @JavascriptInterface
        fun notify(argsJson: String) {
            if (!has(PluginPermission.UI_ACTION)) return
            val args = try { JSONObject(argsJson) } catch (_: Exception) { return }
            val title = args.optString("title", plugin.name)
            val message = args.optString("message")
            if (message.isBlank()) return
            PluginNotifier.notify(context, plugin.id, title, message)
        }

        @JavascriptInterface
        fun registerAiProvider(json: String): String {
            if (!has(PluginPermission.AI_PROVIDER)) return ""
            val provider = try { PluginAiLogic.parseProvider(plugin.id, plugin.name, JSONObject(json)) } catch (_: Exception) { null }
                ?: return ""
            aiProviderMap[provider.id] = provider
            PluginManager.notifyAiProvidersChanged()
            return provider.id
        }

        @JavascriptInterface
        fun setAiModels(providerId: String, modelsJson: String) {
            if (!has(PluginPermission.AI_PROVIDER)) return
            val models = try { PluginAiLogic.parseModels(org.json.JSONArray(modelsJson)) } catch (_: Exception) { return }
            synchronized(aiProviderMap) {
                val cur = aiProviderMap[providerId] ?: return
                aiProviderMap[providerId] = cur.copy(models = models)
            }
            PluginManager.notifyAiProvidersChanged()
        }

        @JavascriptInterface
        fun aiPartial(requestId: String, text: String) {
            val p = pendingGenerations[requestId] ?: return
            if (p.acc.length > MAX_AI_PARTIAL_CHARS) return
            p.acc.append(text)
            p.onPartial(p.acc.toString())
        }

        @JavascriptInterface
        fun aiDone(requestId: String, ok: Boolean, payloadJson: String) {
            val p = pendingGenerations[requestId] ?: return
            val o = try { JSONObject(payloadJson) } catch (_: Exception) { JSONObject() }
            p.deferred.complete(
                if (ok) AiOutcome.Ok(o.opt("value"))
                else AiOutcome.Fail(o.optString("kind"), o.optString("message"), o.optInt("status", 0))
            )
        }

        private fun err(reason: String) = JSONObject().put("error", reason).toString()

        private fun resolveJs(callbackId: Int, ok: Boolean, resultJson: String) {
            val wv = webView ?: return
            val js = "window.__nanoResolve($callbackId, $ok, ${JSONObject.quote(resultJson)});"
            mainHandler.post { wv.evaluateJavascript(js, null) }
        }
    }
}
