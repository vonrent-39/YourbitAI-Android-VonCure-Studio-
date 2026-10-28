package com.youraicqa.app.local

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

object LocalGenerationHolder {
    private const val PREFS = "nanoai"
    private const val PARTIAL_THROTTLE_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    interface UiListener {
        fun onPartial(chatId: String, text: String)
        fun onPersisted(chatId: String)
    }

    @Volatile var uiListener: UiListener? = null

    class Request internal constructor(val chatId: String, val modelName: String) {
        private val partial = StringBuilder()
        @Volatile internal var started = false
        @Volatile internal var finished = false
        @Volatile internal var cancelled = false
        @Volatile internal var detached = false
        @Volatile internal var consumed = false
        @Volatile internal var stopHit = false
        @Volatile internal var result: String? = null
        @Volatile internal var error: Throwable? = null
        internal var job: Job? = null
        internal val done = CompletableDeferred<Unit>()
        @Volatile internal var lastPartialPost = 0L

        val isRunning: Boolean get() = !finished
        fun partialText(): String = synchronized(partial) { partial.toString() }
        internal fun appendPartial(piece: String) = synchronized(partial) { partial.append(piece); Unit }
    }

    private val running = mutableListOf<Request>()

    fun runningFor(chatId: String): Request? =
        synchronized(running) { running.lastOrNull { it.chatId == chatId && !it.finished && !it.cancelled } }

    fun start(
        appContext: Context,
        chatId: String,
        modelName: String,
        maxTokens: Int,
        buildPrompt: (extraDrop: Int) -> String?,
        postProcess: (String) -> String
    ): Request {
        val ctx = appContext.applicationContext
        val req = Request(chatId, modelName)
        synchronized(running) { running.add(req) }
        LocalGenerationService.start(ctx)

        req.job = scope.launch {
            try {
                var extraDrop = 0
                var text = ""
                while (true) {
                    val prompt = buildPrompt(extraDrop)
                        ?: throw Exception(
                            "Запрос не помещается в контекст модели «$modelName» даже без истории диалога " +
                                "(слишком длинное сообщение или системный промпт). Увеличьте размер контекста " +
                                "модели в «Своих моделях» или сократи сообщение."
                        )
                    text = LocalLlamaEngine.generate(
                        prompt, maxTokens,
                        onStarted = { req.started = true },
                        onToken = { piece -> onToken(req, piece) }
                    )
                    if (text.startsWith(LocalLlamaEngine.CTX_OVERFLOW_MARKER)) {
                        extraDrop += 2
                        continue
                    }
                    break
                }
                if (req.cancelled) { finish(req, null, null); return@launch }
                if (text.isBlank()) {
                    throw Exception(
                        "Локальная модель «$modelName» не сгенерировала ни одного токена ответа. " +
                            "Попробуйте начать новый чат покороче, увеличить контекст модели в «Своих моделях» " +
                            "или переключить пресет формата промпта."
                    )
                }
                finish(req, postProcess(text), null)
            } catch (e: CancellationException) {
                finish(req, null, null)
            } catch (e: Throwable) {
                finish(req, null, e)
            }
        }
        return req
    }

    private val endOfTurn = Regex("<\\|end\\|>|<end_of_turn>|<\\|im_end\\|>|<\\|eot_id\\|>")
    private val foreignTurn = Regex(
        "<\\|im_start\\|>\\s*(?:user|system)|<\\|start_header_id\\|>\\s*(?:user|system)|" +
            "<\\|(?:user|system)\\|>|<start_of_turn>\\s*user|(?m)^\\s*(?:Пользователь|User|Human)\\s*:"
    )

    private fun onToken(req: Request, piece: String) {
        if (req.stopHit) return
        req.appendPartial(piece)
        val tail = req.partialText()
        val hit = endOfTurn.containsMatchIn(tail) ||
            (tail.isNotBlank() && foreignTurn.find(tail)?.let { it.range.first > 0 } == true)
        if (hit) {
            req.stopHit = true
            LocalLlamaEngine.requestCancel()
            return
        }
        val now = System.currentTimeMillis()
        if (now - req.lastPartialPost < PARTIAL_THROTTLE_MS) return
        req.lastPartialPost = now
        val snapshot = req.partialText()
        main.post {
            if (!req.finished && !req.cancelled) uiListener?.onPartial(req.chatId, snapshot)
        }
    }

    private fun finish(req: Request, text: String?, error: Throwable?) {
        main.post {
            req.result = text
            req.error = error
            req.finished = true
            synchronized(running) { running.remove(req) }
            if (!req.cancelled && req.detached && !req.consumed) persistDetached(req)
            req.done.complete(Unit)
            LocalGenerationService.stopIfIdle { synchronized(running) { running.isEmpty() } }
        }
    }

    suspend fun await(req: Request): String {
        req.done.await()
        if (req.cancelled) throw CancellationException("Генерация остановлена")
        req.consumed = true
        req.error?.let { throw it }
        return req.result ?: throw CancellationException("Генерация остановлена")
    }

    fun detach(req: Request) {
        main.post {
            if (req.cancelled) return@post
            req.detached = true
            if (req.finished && !req.consumed) persistDetached(req)
        }
    }

    fun cancelFor(chatId: String) {
        val list = synchronized(running) { running.filter { it.chatId == chatId } }
        for (req in list) {
            req.cancelled = true
            if (req.started) LocalLlamaEngine.requestCancel()
            req.job?.cancel()
        }
    }

    private fun persistDetached(req: Request) {
        if (req.consumed) return
        req.consumed = true
        val ctx = LocalGenerationService.appContextOrNull() ?: return
        val text = req.result
        val err = req.error
        val content = when {
            text != null -> text
            err != null -> "⚠️ " + (err.message ?: err.javaClass.simpleName)
            else -> return
        }
        try {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val idx = JSONArray(prefs.getString("chats_index", "[]"))
            var exists = false
            for (i in 0 until idx.length()) if (idx.optJSONObject(i)?.optString("id") == req.chatId) exists = true
            if (!exists) return
            val key = "chat_${req.chatId}_messages"
            val arr = JSONArray(prefs.getString(key, "[]"))
            arr.put(
                JSONObject().put("role", "assistant").put("content", content)
                    .put("ts", System.currentTimeMillis())
                    .put("usage_tokens", content.length / 3)
            )
            prefs.edit().putString(key, arr.toString()).apply()
        } catch (_: Exception) {
            return
        }
        LocalGenerationService.notifyDone(ctx, req.modelName)
        uiListener?.onPersisted(req.chatId)
    }
}
