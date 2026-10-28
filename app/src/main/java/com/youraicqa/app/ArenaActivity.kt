package com.youraicqa.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import java.io.File
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

data class ArenaModelRef(val key: String, val name: String)

data class ArenaConfig(val models: List<ArenaModelRef>, val separate: Boolean)

class ArenaAnswer(
    var text: String = "",
    var error: Boolean = false,
    var done: Boolean = false,
    var feedback: String = "",
    val files: MutableMap<String, String> = linkedMapOf()
)

class ArenaAttachment(val previewPath: String, val gifPath: String?)

class ArenaTurn(
    val ts: Long,
    val users: Map<String, String>,
    val answers: MutableMap<String, ArenaAnswer>,
    var selected: String,
    var userPicked: Boolean = false,
    val attachments: List<ArenaAttachment> = emptyList(),
    var imageContext: String = "",
    val fileNames: List<String> = emptyList(),
    val fileContext: String = "",
    var shownUser: String? = null
)

object ArenaStore {
    const val MAX_MODELS = 50
    private const val PREFS = "nanoai"
    private const val KEY_CONFIG = "arena_config"
    private const val KEY_HISTORY = "arena_history"
    private const val MAX_TURNS = 40

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadConfig(c: Context): ArenaConfig {
        val raw = prefs(c).getString(KEY_CONFIG, null) ?: return ArenaConfig(emptyList(), false)
        return try {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("models") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = m.optString("key")
                if (k.isBlank()) null else ArenaModelRef(k, m.optString("name").ifBlank { k })
            }
            ArenaConfig(list.take(MAX_MODELS), o.optBoolean("separate", false))
        } catch (_: Exception) {
            ArenaConfig(emptyList(), false)
        }
    }

    fun saveConfig(c: Context, cfg: ArenaConfig) {
        val arr = JSONArray()
        cfg.models.forEach { arr.put(JSONObject().put("key", it.key).put("name", it.name)) }
        prefs(c).edit().putString(KEY_CONFIG, JSONObject().put("models", arr).put("separate", cfg.separate).toString()).apply()
    }

    fun loadTurns(c: Context): MutableList<ArenaTurn> {
        val raw = prefs(c).getString(KEY_HISTORY, null) ?: return mutableListOf()
        val out = mutableListOf<ArenaTurn>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val usersObj = t.optJSONObject("users") ?: continue
                val users = linkedMapOf<String, String>()
                usersObj.keys().forEach { k -> users[k] = usersObj.optString(k) }
                if (users.isEmpty()) continue
                val ansObj = t.optJSONObject("answers") ?: JSONObject()
                val answers = linkedMapOf<String, ArenaAnswer>()
                for (k in users.keys) {
                    val a = ansObj.optJSONObject(k)
                    answers[k] = if (a != null) ArenaAnswer(a.optString("t"), a.optBoolean("e"), true, a.optString("fb")).also { ans ->
                        a.optJSONObject("f")?.let { fo -> fo.keys().forEach { fk -> ans.files[fk] = fo.optString(fk) } }
                    }
                    else ArenaAnswer("Ответ не получен: приложение закрыли до завершения.", error = true, done = true)
                }
                val sel = t.optString("sel").takeIf { it in users } ?: users.keys.first()
                val atts = mutableListOf<ArenaAttachment>()
                t.optJSONArray("att")?.let { aa ->
                    for (j in 0 until aa.length()) {
                        val ao = aa.optJSONObject(j) ?: continue
                        val pp = ao.optString("p")
                        if (pp.isNotBlank() && File(pp).exists()) atts.add(ArenaAttachment(pp, ao.optString("g").takeIf { it.isNotBlank() }))
                    }
                }
                out.add(ArenaTurn(t.optLong("ts"), users, answers, sel, userPicked = true, attachments = atts, imageContext = t.optString("ctx"),
                    fileNames = t.optJSONArray("fn")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                    fileContext = t.optString("fx")))
            }
        } catch (_: Exception) { }
        return out
    }

    fun saveTurns(c: Context, turns: List<ArenaTurn>) {
        val arr = JSONArray()
        for (t in turns.takeLast(MAX_TURNS)) {
            val users = JSONObject(); t.users.forEach { (k, v) -> users.put(k, v) }
            val answers = JSONObject()
            t.answers.forEach { (k, a) ->
                if (a.done) {
                    val fo = JSONObject(); a.files.forEach { (fk, fv) -> fo.put(fk, fv) }
                    answers.put(k, JSONObject().put("t", a.text).put("e", a.error).put("fb", a.feedback).put("f", fo))
                }
            }
            val att = JSONArray()
            t.attachments.forEach { att.put(JSONObject().put("p", it.previewPath).put("g", it.gifPath ?: "")) }
            arr.put(JSONObject().put("ts", t.ts).put("sel", t.selected).put("users", users).put("answers", answers)
                .put("att", att).put("ctx", t.imageContext)
                .put("fn", JSONArray(t.fileNames)).put("fx", t.fileContext.take(12000)))
        }
        prefs(c).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun clearTurns(c: Context) {
        prefs(c).edit().remove(KEY_HISTORY).apply()
    }
}

object ArenaModels {
    fun keyOf(m: MainActivity.Model): String =
        listOf(m.provider, m.id, m.customProviderUrl, m.serverId).joinToString("|") + (if (m.modeTag.isNotBlank()) "|M:" + m.modeTag else "")

    fun available(ctx: Context): List<MainActivity.Model> {
        val servers = ServerStore.loadEnabled(ctx)
        val replaced = ServerLogic.replacedBy(servers)
        val builtin = BuiltinModels.list.filter { !it.isImage }.map { b ->
            val s = replaced[b.id]
            if (s == null) b else b.copy(
                provider = ApiKeyGate.PROVIDER_SERVER,
                serverId = s.id,
                desc = "Через сервер ${s.name.ifBlank { s.baseUrl }}"
            )
        }
        val replacedPairs = builtin.filter { it.serverId.isNotBlank() }.map { it.id to it.serverId }.toSet()
        val user = ModelsStore.load(ctx).filter { it.source != "local" }
            .flatMap { cm -> ModelModes.expand(cm) }
            .filter { !it.isImage }
        val serverModels = ServerLogic.serverModelEntries(servers)
            .filterNot { (it.modelId to it.serverId) in replacedPairs }
            .map { e ->
                MainActivity.Model(
                    name = e.modelId, provider = ApiKeyGate.PROVIDER_SERVER, id = e.modelId, badge = "SRV",
                    desc = "Модель сервера ${e.serverName}", accent = 0xFF80CBC4.toInt(), serverId = e.serverId
                )
            }
        return (builtin + user + serverModels).distinctBy { keyOf(it) }
    }

    fun find(ctx: Context, key: String): MainActivity.Model? = available(ctx).firstOrNull { keyOf(it) == key }

    fun apiKey(ctx: Context, m: MainActivity.Model): String {
        if (m.serverId.isNotBlank()) return ""
        if (m.customProviderUrl.isNotBlank()) {
            val own = ModelsStore.load(ctx).firstOrNull { it.id == m.id && it.providerUrl == m.customProviderUrl }?.apiKey.orEmpty()
            return own.ifBlank { ProvidersStore.keyForUrl(ctx, m.customProviderUrl) }
        }
        return ctx.applicationContext.getSharedPreferences("nanoai", Context.MODE_PRIVATE)
            .getString(m.provider, "")?.trim().orEmpty()
    }
}

class ArenaActivity : NanoBaseActivity() {
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var inputBar: LinearLayout

    private var config = ArenaConfig(emptyList(), false)
    private val turns = mutableListOf<ArenaTurn>()
    private val turnViews = HashMap<Long, LinearLayout>()
    private var pending = 0
    private var activeJob: Job? = null

    private val md by lazy { ChatMarkdown(this, { pending > 0 }, { text -> sendSuggestion(text) }) }

    private var singleInput: EditText? = null
    private val separateInputs = LinkedHashMap<String, EditText>()
    private var sendBtn: ImageView? = null
    private var gearBtn: ImageView? = null

    private val pendingImages = mutableListOf<ChatImages.Loaded>()
    private var previewScroll: HorizontalScrollView? = null
    private var previewRow: LinearLayout? = null

    private val pendingFiles = mutableListOf<ChatAttach.FileItem>()
    private val PICK_FILE_REQ = 7412
    private val sendGate = kotlinx.coroutines.sync.Semaphore(6)

    private val PICK_IMAGE_REQ = 7411
    private val MAX_FRAMES = 5

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = ArenaStore.loadConfig(this)
        turns.addAll(ArenaStore.loadTurns(this))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@ArenaActivity, R.color.app_background))
        }
        root.addView(buildHeader())
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            clipToPadding = false
        }
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(12))
        }
        scroll.addView(chat, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll)
        inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.addView(inputBar)
        setContentView(root)

        renderAll()
        rebuildInputBar()
    }

    override fun onResume() {
        super.onResume()
        val avail = ArenaModels.available(this).map { ArenaModels.keyOf(it) }.toSet()
        val kept = config.models.filter { it.key in avail }
        if (kept.size != config.models.size) {
            config = config.copy(models = kept)
            ArenaStore.saveConfig(this, config)
            rebuildInputBar()
            renderAll()
        }
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQ && resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) addAttachment(clip.getItemAt(i).uri)
            } else {
                data.data?.let { addAttachment(it) }
            }
        }
        if (requestCode == PICK_FILE_REQ && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            if (ChatAttach.isImage(this, uri)) { addAttachment(uri); return }
            if (pendingFiles.size >= 5) {
                Toast.makeText(this, Loc.t("Максимум 5 файлов"), Toast.LENGTH_SHORT).show()
                return
            }
            try {
                pendingFiles.add(ChatAttach.readFile(this, uri))
                refreshPreviews(); updateSendState()
            } catch (e: Exception) {
                Toast.makeText(this, Loc.t("Не удалось открыть файл: ${e.message}"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(12), dp(12))
        }
        row.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arena_vs)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(8) }
        })
        row.addView(TextView(this).apply {
            text = "Арена"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val gear = ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            contentDescription = "Настройки Арены"
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { showConfig() }
            visibility = if (turns.isEmpty()) View.INVISIBLE else View.VISIBLE
        }
        gearBtn = gear
        row.addView(gear)
        return row
    }

    private fun updateGear() {
        gearBtn?.visibility = if (turns.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun renderAll() {
        chat.removeAllViews()
        turnViews.clear()
        updateGear()
        if (turns.isEmpty()) {
            chat.addView(buildEmptyState())
            return
        }
        for (t in turns) chat.addView(buildTurnView(t, animate = false))
        scrollToEnd()
    }

    private fun buildEmptyState(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(48), dp(24), dp(24))
        addView(ImageView(this@ArenaActivity).apply {
            setImageResource(R.drawable.ic_arena_vs)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        addView(TextView(this@ArenaActivity).apply {
            text = "Сравни модели в одном чате"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })
        addView(TextView(this@ArenaActivity).apply {
            text = "Выберите от 2 до ${ArenaStore.MAX_MODELS} моделей (хоть всё сразу), отправьте сообщение — и переключайте ответы моделей над сообщением. " +
                "Можно слать одно сообщение всем или своё каждой модели."
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 14f
            gravity = Gravity.CENTER
        })
        addView(pillButton(if (config.models.isEmpty()) "Выбрать модели" else "Настроить модели") { showConfig() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(20)
        })
    }

    private fun pillButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        textSize = 14.5f
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(11), dp(20), dp(11))
        background = GradientDrawable().apply { setColor(Th.bg(0xFF2A2A2A.toInt())); cornerRadius = dp(22).toFloat() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        applyPressAnimation(PressAnimStyle.LIFT)
        setOnClickListener { onClick() }
    }

    private fun cardBackground(): android.graphics.drawable.Drawable? =
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_message_card)?.mutate()

    private fun cardLayoutParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(6); bottomMargin = dp(6)
    }

    private fun entrance(v: View) {
        if (!UiPrefs.animationsEnabled(this)) return
        v.alpha = 0f
        v.scaleX = 0.92f
        v.scaleY = 0.92f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }

    private fun buildTurnView(t: ArenaTurn, animate: Boolean): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        turnViews[t.ts] = box
        fillTurn(box, t, animate)
        return box
    }

    private fun refreshTurn(t: ArenaTurn) {
        val box = turnViews[t.ts] ?: return
        fillTurn(box, t, animate = false)
    }

    private fun fillTurn(box: LinearLayout, t: ArenaTurn, animate: Boolean) {
        box.removeAllViews()
        val sel = t.selected
        val shown = t.shownUser?.takeIf { it in t.users } ?: sel
        val userText = t.users[shown].orEmpty()

        val userCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = cardLayoutParams()
        }
        userCard.addView(TextView(this).apply {
            text = ProfileStore.userName(this@ArenaActivity)
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12f
        })
        if (hasSeparateRequests(t)) userCard.addView(buildRequestChips(t, shown))
        if (t.attachments.isNotEmpty()) userCard.addView(buildAttachmentsRow(t.attachments))
        for (fn in t.fileNames) {
            userCard.addView(TextView(this).apply {
                text = "📎 $fn"
                textSize = 13f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(Th.fg(0xFFB8C7FF.toInt()))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply { setColor(Th.bg(0xFF262633.toInt())); cornerRadius = dp(12).toFloat() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            })
        }
        if (userText.isNotBlank()) {
            userCard.addView(TextView(this).apply {
                text = userText
                setTextColor(Th.fg(0xFFE8E8E8.toInt()))
                textSize = 16f
                setTextIsSelectable(true)
                setLineSpacing(0f, 1.15f)
            })
            userCard.addView(buildActionsRow(t.ts, userText, null, t))
        }
        box.addView(userCard)

        val aiCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(10), dp(14), dp(12))
            layoutParams = cardLayoutParams()
        }
        aiCard.addView(buildChips(t))
        val ans = t.answers[sel]
        aiCard.addView(buildAnswerBody(t, sel, ans))
        if (ans != null && ans.done && !ans.error && ans.text.isNotBlank()) {
            aiCard.addView(buildActionsRow(t.ts, md.splitSuggestions(ans.text).first, Triple(t, sel, ans)))
        }
        box.addView(aiCard)

        if (animate) { entrance(userCard); entrance(aiCard) }
    }

    private fun buildAttachmentsRow(list: List<ArenaAttachment>): View {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in list) {
            val lp = LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8); topMargin = dp(8); bottomMargin = dp(6) }
            val outline = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            if (a.gifPath != null && AnimatedGifView.canPlay(a.gifPath)) {
                inner.addView(AnimatedGifView(this).apply {
                    loadFromFile(a.gifPath)
                    layoutParams = lp
                    clipToOutline = true
                    outlineProvider = outline
                    setOnClickListener {
                        startActivity(Intent(this@ArenaActivity, FilePreviewActivity::class.java).apply {
                            putExtra("title", "ГИФ"); putExtra("path", a.gifPath); putExtra("mime", "image/gif")
                        })
                    }
                })
            } else {
                val bmp = ChatImages.decodePreview(a.previewPath, 400) ?: continue
                inner.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    layoutParams = lp
                    clipToOutline = true
                    outlineProvider = outline
                    setOnClickListener {
                        startActivity(Intent(this@ArenaActivity, FilePreviewActivity::class.java).apply {
                            putExtra("title", "Изображение"); putExtra("path", a.previewPath); putExtra("mime", "image/jpeg")
                        })
                    }
                })
            }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner)
        }
    }

    private fun buildAnswerBody(t: ArenaTurn, key: String, ans: ArenaAnswer?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        fun plain(text: String, color: Int, pulseIt: Boolean = false, selectable: Boolean = false) {
            box.addView(TextView(this).apply {
                this.text = text
                setTextColor(Th.fg(color))
                textSize = 16f
                setLineSpacing(0f, 1.15f)
                if (selectable) setTextIsSelectable(true)
                if (pulseIt) pulse(this)
            })
        }
        when {
            ans == null || (!ans.done && ans.text.isBlank()) -> plain("Думает…", 0xFF888888.toInt(), pulseIt = true)
            ans.error -> plain(ans.text, 0xFFE57373.toInt(), selectable = true)
            !ans.done && ChatTools.looksLikeQuiz(ans.text) -> plain("Готовлю квиз…", 0xFF888888.toInt(), pulseIt = true)
            ChatTools.looksLikeQuiz(ans.text) -> {
                val quiz = ChatTools.parseQuiz(ans.text)
                if (quiz != null) {
                    box.addView(QuizCardView(this) { qi, oi -> answerQuiz(t, key, qi, oi) }.build(quiz))
                } else {
                    plain("⚠ Не удалось отобразить квиз: модель прислала повреждённые данные. Нажмите «Переработать».", 0xFFE57373.toInt())
                }
            }
            else -> {
                val (clean, suggestions) = md.splitSuggestions(ans.text)
                addSegments(box, md.prepare(clean), ans)
                if (ans.done && suggestions.isNotEmpty() && t === turns.lastOrNull()) box.addView(md.makeSuggestions(suggestions))
            }
        }
        return box
    }

    private fun answerQuiz(t: ArenaTurn, key: String, qi: Int, oi: Int) {
        val ans = t.answers[key] ?: return
        val quiz = ChatTools.parseQuiz(ans.text) ?: return
        val qs = quiz.optJSONArray("questions") ?: return
        if (qi !in 0 until qs.length()) return
        val q = qs.getJSONObject(qi)
        if (!q.isNull("answered") && q.optInt("answered", -1) >= 0) return
        q.put("answered", oi)
        ans.text = ChatTools.QUIZ_MARKER + quiz.toString()
        ArenaStore.saveTurns(this, turns)
        refreshTurn(t)
    }

    private var htmlCtxText: String = ""

    private fun addSegments(box: LinearLayout, text: String, ans: ArenaAnswer) {
        htmlCtxText = text
        data class Hit(val range: IntRange, val view: View)
        val hits = mutableListOf<Hit>()
        for (blk in ChatTools.scanFencedBlocks(text, "nanofile")) {
            val name = ans.files[ChatTools.fileKey(false, blk.header)]
                ?: blk.header.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "файл.txt" }
            hits.add(Hit(blk.range, makeFileCard(name, "")))
        }
        for (blk in ChatTools.scanFencedBlocks(text, "nanozip")) {
            val raw = blk.header.removePrefix("create:").removePrefix("Create:").trim().trimStart(':').trim()
            val fallback = raw.let { if (it.endsWith(".zip", true)) it else "$it.zip" }
            val name = ans.files[ChatTools.fileKey(true, blk.header)] ?: fallback
            hits.add(Hit(blk.range, makeFileCard(name, "Архив создан нейросетью")))
        }
        hits.sortBy { it.range.first }
        var last = 0
        for (h in hits) {
            if (h.range.first < last) continue
            addTextAndCode(box, text.substring(last, h.range.first))
            box.addView(h.view)
            last = h.range.last + 1
        }
        addTextAndCode(box, text.substring(last.coerceAtMost(text.length)))
    }

    private fun addTextAndCode(box: LinearLayout, chunk: String) {
        if (chunk.isBlank()) return
        val re = Regex("(?s)```([A-Za-z0-9_+\\-]*)[ \\t]*\\n(.*?)(```|\\z)")
        var lastEnd = 0
        for (m in re.findAll(chunk)) {
            val before = chunk.substring(lastEnd, m.range.first).trim('\n')
            if (before.isNotBlank()) addPlainText(box, before)
            val code = m.groupValues[2].trimEnd('\n')
            if (m.groupValues[1].equals("poll", ignoreCase = true)) {
                if (code.isNotBlank()) md.renderText(box, "```poll\n$code\n```")
            } else if (code.isNotBlank()) box.addView(makeCodeBlock(code, m.groupValues[1]))
            lastEnd = m.range.last + 1
        }
        val tail = chunk.substring(lastEnd.coerceAtMost(chunk.length)).trim('\n')
        if (tail.isNotBlank()) addPlainText(box, tail)
    }

    private fun addPlainText(box: LinearLayout, text: String) {
        md.renderText(box, text)
    }

    private fun sendSuggestion(text: String) {
        if (pending > 0 || config.models.size < 2) return
        startTurn(config.models.associate { it.key to text }, emptyList())
    }

    private fun makeCodeBlock(code: String, lang: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_code_block)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(10)
            }
        }
        val header = FrameLayout(this).apply { setPadding(dp(12), dp(8), dp(8), dp(6)) }
        header.addView(TextView(this).apply {
            text = if (lang.isNotBlank()) lang else "code"
            setTextColor(Th.fg(0xFF9AA0A6.toInt()))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
        val isHtml = HtmlPreviewActivity.isHtml(code, lang)
        header.addView(miniBtn(R.drawable.ic_copy, "Скопировать") { copyText(code) },
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.END or Gravity.CENTER_VERTICAL))
        if (isHtml) {
            val ctxText = htmlCtxText
            header.addView(TextView(this).apply {
                text = Ico.glyphWithText(context, R.drawable.ic_play, 13f, Th.fg(0xFF7CFFB2.toInt()), Loc.t("Предпросмотр"))
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Th.fg(0xFF7CFFB2.toInt()))
                setPadding(dp(10), 0, dp(10), 0)
                background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_copy_btn)
                setOnClickListener { HtmlPreviewActivity.open(this@ArenaActivity, code, ctxText) }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(44) })
        }
        card.addView(header)
        card.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Th.bg(0xFF3A3D45.toInt()))
        })
        val codeTv = TextView(this).apply {
            text = if (EconomyPrefs.syntaxHighlightEnabled(this@ArenaActivity)) SyntaxHighlighter.highlight(code, lang) else code
            typeface = Typeface.MONOSPACE
            setTextColor(Th.fg(0xFFE0E0E0.toInt()))
            textSize = 13.5f
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(8), dp(14), dp(14))
        }
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(codeTv)
        })
        return card
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f КБ", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0))
    }

    private fun makeFileCard(fileName: String, subtitle: String): View {
        val f = filesDir.resolve("NanoAI/Files").resolve(fileName)
        val sizeStr = if (f.exists()) formatSize(f.length()) else "—"
        val kind = when {
            fileName.endsWith(".zip", true) -> "ZIP архив"
            fileName.endsWith(".py", true) -> "Python"
            fileName.endsWith(".txt", true) -> "Текст"
            else -> "Файл"
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1E24.toInt())); cornerRadius = dp(16).toFloat() }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8); bottomMargin = dp(6)
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply {
            setImageResource(when {
                fileName.endsWith(".zip", true) -> R.drawable.ic_file_zip
                fileName.endsWith(".py", true) -> R.drawable.ic_file_py
                fileName.endsWith(".txt", true) || fileName.endsWith(".md", true) -> R.drawable.ic_file_txt
                fileName.endsWith(".html", true) -> R.drawable.ic_file_html
                fileName.endsWith(".jpg", true) || fileName.endsWith(".png", true) || fileName.endsWith(".webp", true) -> R.drawable.ic_file_jpg
                fileName.endsWith(".jpeg", true) -> R.drawable.ic_file_jpeg
                else -> R.drawable.ic_file_unknown
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = fileName
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "$sizeStr · $kind"
            setTextColor(Th.fg(0xFFAAAAAA.toInt()))
            textSize = 12f
        })
        if (subtitle.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = subtitle
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }
        top.addView(col)
        card.addView(top)
        val dlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Th.bg(0xFF2A2A32.toInt())); cornerRadius = dp(22).toFloat() }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { downloadToDownloads(f, fileName) }
        }
        dlRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_download)
            setColorFilter(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(5) }
        })
        dlRow.addView(TextView(this).apply {
            text = "Скачать"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14f
        })
        card.addView(dlRow)
        card.applyPressAnimation(PressAnimStyle.LIFT)
        card.setOnClickListener {
            if (!f.exists()) { Toast.makeText(this, Loc.t("Файл не найден"), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            startActivity(Intent(this, FilePreviewActivity::class.java).apply {
                putExtra("title", fileName); putExtra("path", f.absolutePath); putExtra("mime", "*/*")
            })
        }
        return card
    }

    private fun downloadToDownloads(f: File, displayName: String) {
        try {
            if (!f.exists()) { Toast.makeText(this, Loc.t("Файл не найден"), Toast.LENGTH_SHORT).show(); return }
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
                android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val outUri = contentResolver.insert(collection, values) ?: throw Exception("Не удалось создать файл в Downloads")
            contentResolver.openOutputStream(outUri)?.use { outs -> f.inputStream().use { it.copyTo(outs) } }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(outUri, values, null, null)
            }
            Toast.makeText(this, Loc.t("Сохранено в Загрузки: $displayName"), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Скачивание: ${e.message}"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("arena", text))
        Toast.makeText(this, Loc.t("Скопировано"), Toast.LENGTH_SHORT).show()
    }

    private fun miniBtn(icon: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        ActionIcons.apply(this, icon)
        setColorFilter(Th.fg(0xFFCCCCCC.toInt()))
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_copy_btn)
        contentDescription = desc
        applyPressAnimation(PressAnimStyle.POP)
        setOnClickListener { onClick() }
    }

    private fun buildActionsRow(ts: Long, text: String, ai: Triple<ArenaTurn, String, ArenaAnswer>?, userTurn: ArenaTurn? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_msg_time)
            setColorFilter(Th.fg(0xFF888888.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(2) }
        })
        timeRow.addView(TextView(this).apply {
            this.text = android.text.format.DateFormat.getTimeFormat(this@ArenaActivity).format(java.util.Date(ts))
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12f
        })
        row.addView(timeRow)

        val gap = dp(9)
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun add(v: View, last: Boolean = false) {
            btns.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { if (!last) marginEnd = gap })
        }
        if (ai != null) {
            val (turn, key, ans) = ai
            val like = ImageView(this)
            val dislike = ImageView(this)
            fun refreshIcons() {
                like.setColorFilter(if (ans.feedback == "like") Th.fg(0xFF7CFFB2.toInt()) else Th.fg(0xFFCCCCCC.toInt()))
                dislike.setColorFilter(if (ans.feedback == "dislike") Th.fg(0xFFFF6B6B.toInt()) else Th.fg(0xFFCCCCCC.toInt()))
            }
            fun setFeedback(kind: String) {
                ans.feedback = if (ans.feedback == kind) "" else kind
                refreshIcons()
                ArenaStore.saveTurns(this, turns)
            }
            like.apply {
                ActionIcons.apply(this, R.drawable.ic_like)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_copy_btn)
                contentDescription = "Хороший ответ"
                applyPressAnimation(PressAnimStyle.POP)
                setOnClickListener { setFeedback("like") }
            }
            dislike.apply {
                ActionIcons.apply(this, R.drawable.ic_dislike)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_copy_btn)
                contentDescription = "Плохой ответ"
                applyPressAnimation(PressAnimStyle.POP)
                setOnClickListener { setFeedback("dislike") }
            }
            refreshIcons()
            add(like); add(dislike)
            add(miniBtn(R.drawable.ic_copy, "Копировать") { copyText(text) })
            val canRegen = turns.isNotEmpty() && turns.last() === turn
            add(miniBtn(R.drawable.ic_share, "Поделиться") {
                try {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "Поделиться"))
                } catch (e: Exception) {
                    Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
                }
            }, last = !canRegen)
            if (canRegen) add(miniBtn(R.drawable.ic_regenerate, "Переработать") { regenerate(turn, key) }, last = true)
        } else {
            if (userTurn != null) {
                if (turns.lastOrNull() === userTurn) add(miniBtn(R.drawable.ic_pencil_asset, "Изменить") { editLastTurn(userTurn) })
                add(miniBtn(R.drawable.ic_trash, "Удалить") { deleteTurn(userTurn) })
            }
            add(miniBtn(R.drawable.ic_copy, "Копировать") { copyText(text) }, last = true)
        }
        row.addView(btns)
        return row
    }

    private fun deleteTurn(t: ArenaTurn) {
        if (pending > 0) {
            Toast.makeText(this, Loc.t("Дождитесь ответов моделей"), Toast.LENGTH_SHORT).show()
            return
        }
        styledConfirmDialog("Удалить сообщение?", "Сообщение и ответы всех моделей на него будут удалены.", "Удалить", destructive = true) {
            turns.remove(t)
            ArenaStore.saveTurns(this, turns)
            renderAll()
        }
    }

    private fun editLastTurn(t: ArenaTurn) {
        if (pending > 0) {
            Toast.makeText(this, Loc.t("Дождитесь ответов моделей"), Toast.LENGTH_SHORT).show()
            return
        }
        turns.remove(t)
        ArenaStore.saveTurns(this, turns)
        renderAll()
        if (config.separate) t.users.forEach { (k, v) -> separateInputs[k]?.setText(v) }
        else singleInput?.setText(t.users.values.firstOrNull { it.isNotBlank() }.orEmpty())
        (singleInput ?: separateInputs.values.firstOrNull())?.let { it.requestFocus(); it.setSelection(it.text.length) }
    }

    private fun pulse(v: View) {
        if (!EconomyPrefs.pulseEnabled(this)) return
        val a = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.35f).apply {
            duration = 700
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
        }
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { a.cancel() }
        })
        a.start()
    }

    private fun hasSeparateRequests(t: ArenaTurn): Boolean =
        t.users.size > 1 && t.users.values.map { it.trim() }.toSet().size > 1

    private fun buildRequestChips(t: ArenaTurn, shown: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        var n = 0
        for ((key, _) in t.users) {
            n++
            val ref = config.models.firstOrNull { it.key == key }
            val name = ref?.name ?: key.split("|").getOrNull(1).orEmpty()
            val active = key == shown
            row.addView(TextView(this).apply {
                text = "$n · $name"
                textSize = 12f
                maxLines = 1
                setTextColor(if (active) Th.fg(0xFFFFFFFF.toInt()) else Th.fg(0xFF9A9AA5.toInt()))
                if (active) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Th.bg(if (active) 0xFF34343C.toInt() else 0xFF222226.toInt()))
                    if (active) setStroke(dp(1), Th.line(0xFFB39DFF.toInt()))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener {
                    if (!active) {
                        t.shownUser = key
                        refreshTurn(t)
                    }
                }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(8)
            }
            addView(row)
        }
    }

    private fun buildChips(t: ArenaTurn): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((key, _) in t.users) {
            val ref = config.models.firstOrNull { it.key == key }
            val name = ref?.name ?: key.split("|").getOrNull(1).orEmpty()
            val a = t.answers[key]
            val suffix = when {
                a == null || !a.done -> " …"
                a.error -> " ⚠"
                else -> ""
            }
            val selected = key == t.selected
            row.addView(TextView(this).apply {
                text = name + suffix
                textSize = 13f
                maxLines = 1
                setTextColor(if (selected) Th.fg(0xFFFFFFFF.toInt()) else Th.fg(0xFFAAAAAA.toInt()))
                if (selected) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Th.bg(if (selected) 0xFF3A3A44.toInt() else 0xFF222226.toInt()))
                    if (selected) setStroke(dp(1), Th.line(0xFF8AB4F8.toInt()))
                }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener {
                    if (t.selected != key) {
                        t.selected = key
                        t.userPicked = true
                        t.shownUser = null
                        refreshTurn(t)
                        ArenaStore.saveTurns(this@ArenaActivity, turns)
                    }
                }
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun renderLite(text: String): CharSequence {
        val sb = SpannableStringBuilder()
        var inCode = false
        val lines = text.split("\n")
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trimEnd()
            val start = sb.length
            if (line.trimStart().startsWith("```")) { inCode = !inCode; continue }
            when {
                inCode -> {
                    sb.append(line)
                    sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(Th.bg(0xFF262626.toInt())), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.92f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("#") -> {
                    sb.append(line.trimStart('#').trim())
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.12f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    sb.append("•  ")
                    appendInline(sb, line.substring(2))
                }
                else -> appendInline(sb, line)
            }
            if (idx < lines.lastIndex) sb.append('\n')
        }
        return sb
    }

    private fun appendInline(sb: SpannableStringBuilder, line: String) {
        val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|~~(?=[^\\s~])(.+?)(?<=[^\\s~])~~")
        var last = 0
        for (m in re.findAll(line)) {
            sb.append(line, last, m.range.first)
            val s = sb.length
            if (m.groupValues[1].isNotEmpty()) {
                sb.append(m.groupValues[1])
                sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (m.groupValues[3].isNotEmpty()) {
                sb.append(m.groupValues[3])
                sb.setSpan(android.text.style.StrikethroughSpan(), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.append(m.groupValues[2])
                sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(BackgroundColorSpan(Th.bg(0xFF2E2E2E.toInt())), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            last = m.range.last + 1
        }
        sb.append(line, last, line.length)
    }

    private fun styledEdit(hintText: String): EditText = RichContentEditText(this).apply {
        hint = hintText
        setHintTextColor(Th.fg(0xFF777777.toInt()))
        setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        textSize = 15f
        maxLines = 4
        minHeight = dp(46)
        gravity = Gravity.CENTER_VERTICAL
        background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_input_field)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        onRichContent = { uri, _ -> addAttachment(uri) }
    }

    private fun addAttachment(uri: Uri): Boolean {
        if (pendingImages.size >= MAX_FRAMES) {
            Toast.makeText(this, Loc.t("Максимум $MAX_FRAMES изображений"), Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val loaded = ChatImages.load(this, uri)
            val used = pendingImages.sumOf { it.frames.size }
            val frames = if (used + loaded.frames.size > MAX_FRAMES) loaded.frames.take((MAX_FRAMES - used).coerceAtLeast(1)) else loaded.frames
            pendingImages.add(ChatImages.Loaded(frames, loaded.previewPath, loaded.gifPath))
            refreshPreviews()
            updateSendState()
            true
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Не удалось загрузить изображение: ${e.message}"), Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun refreshPreviews() {
        val rowV = previewRow ?: return
        rowV.removeAllViews()
        previewScroll?.visibility = if (pendingImages.isEmpty() && pendingFiles.isEmpty()) View.GONE else View.VISIBLE
        for (f in pendingFiles.toList()) {
            rowV.addView(TextView(this).apply {
                text = "📎 ${f.name} · ${f.sizeLabel}  ✕"
                textSize = 12.5f; maxLines = 1; maxWidth = dp(220)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(Th.fg(0xFFE8E8E8.toInt()))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { setColor(Th.bg(0xFF2A2A32.toInt())); cornerRadius = dp(14).toFloat() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8); gravity = Gravity.CENTER_VERTICAL
                }
                setOnClickListener { pendingFiles.remove(f); refreshPreviews(); updateSendState() }
            })
        }
        for (img in pendingImages) {
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(8) }
            }
            val outline = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
            }
            if (img.gifPath != null && AnimatedGifView.canPlay(img.gifPath)) {
                cell.addView(AnimatedGifView(this).apply {
                    loadFromFile(img.gifPath)
                    clipToOutline = true
                    outlineProvider = outline
                }, FrameLayout.LayoutParams(dp(72), dp(72)))
            } else {
                cell.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(ChatImages.decodePreview(img.previewPath, 200))
                    clipToOutline = true
                    outlineProvider = outline
                }, FrameLayout.LayoutParams(dp(72), dp(72)))
            }
            cell.addView(TextView(this).apply {
                text = Ico.glyph(context, R.drawable.ic_close, 12f, 0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 12f
                background = GradientDrawable().apply { setColor(0xCC000000.toInt()); shape = GradientDrawable.OVAL }
                setOnClickListener {
                    pendingImages.remove(img)
                    refreshPreviews()
                    updateSendState()
                }
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            rowV.addView(cell)
        }
    }

    private fun rebuildInputBar() {
        inputBar.removeAllViews()
        singleInput = null
        separateInputs.clear()

        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewRow = pr
        previewScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
            addView(pr)
        }
        inputBar.addView(previewScroll)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (config.separate) {
            for (ref in config.models) {
                fields.addView(TextView(this).apply {
                    text = ref.name
                    setTextColor(Th.fg(0xFF8AB4F8.toInt()))
                    textSize = 12f
                    setPadding(dp(4), dp(4), 0, dp(2))
                })
                val et = styledEdit("Сообщение для ${ref.name}")
                separateInputs[ref.key] = et
                fields.addView(et)
            }
            if (config.models.isEmpty()) fields.addView(styledEdit("Сначала выберите модели").apply { isEnabled = false })
        } else {
            val et = styledEdit("Сообщение всем моделям")
            singleInput = et
            fields.addView(et)
        }
        if (config.separate && config.models.size > 3) {
            row.addView(ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(230), 1f)
                addView(fields)
            })
        } else row.addView(fields)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_box)
            setColorFilter(Th.fg(0xFFCCCCCC.toInt()))
            background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_copy_btn)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "Прикрепить файл"
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8); gravity = Gravity.BOTTOM }
            applyPressAnimation(PressAnimStyle.POP)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(intent, "Файл"), PICK_FILE_REQ)
            }
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_photos)
            setColorFilter(Th.fg(0xFFCCCCCC.toInt()))
            background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_copy_btn)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            contentDescription = "Прикрепить фото или ГИФ"
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) }
            applyPressAnimation(PressAnimStyle.POP)
            setOnClickListener {
                if (pendingImages.size >= MAX_FRAMES) {
                    Toast.makeText(this@ArenaActivity, Loc.t("Максимум $MAX_FRAMES изображений"), Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    startActivityForResult(Intent.createChooser(intent, "Фото"), PICK_IMAGE_REQ)
                }
            }
        })
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.ic_send_arrow)
            setColorFilter(0xFFFFFFFF.toInt())
            background = androidx.core.content.ContextCompat.getDrawable(this@ArenaActivity, R.drawable.bg_send_button)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) }
            applyPressAnimation(PressAnimStyle.BOUNCE)
            setOnClickListener { onSend() }
        }
        sendBtn = btn
        row.addView(btn)
        inputBar.addView(row)
        refreshPreviews()
        updateSendState()
    }

    private fun updateSendState() {
        val b = sendBtn ?: return
        b.alpha = 1f
        if (pending > 0) {
            b.setImageResource(R.drawable.ic_stop_square)
            b.setBackgroundResource(R.drawable.bg_send_stop)
            b.contentDescription = "Остановить генерацию"
        } else {
            b.setImageResource(R.drawable.ic_send_arrow)
            b.setBackgroundResource(R.drawable.bg_send_button)
            b.contentDescription = "Отправить"
        }
        b.setColorFilter(0xFFFFFFFF.toInt())
    }

    private fun cancelGeneration() {
        activeJob?.cancel()
        activeJob = null
        var touched: ArenaTurn? = null
        for (turn in turns) {
            var changed = false
            for ((_, a) in turn.answers) {
                if (a.done) continue
                a.done = true
                a.error = false
                a.text = if (a.text.isBlank()) "⏹ Остановлено" else a.text.trimEnd() + "\n\n⏹ Остановлено"
                changed = true
            }
            if (changed) { touched = turn; refreshTurn(turn) }
        }
        pending = 0
        updateSendState()
        if (touched != null) ArenaStore.saveTurns(this, turns)
        Toast.makeText(this, Loc.t("Генерация остановлена"), Toast.LENGTH_SHORT).show()
    }

    private fun onSend() {
        if (pending > 0) {
            cancelGeneration()
            return
        }
        if (config.models.size < 2) {
            Toast.makeText(this, Loc.t("Добавьте минимум две модели"), Toast.LENGTH_SHORT).show()
            showConfig()
            return
        }
        val hasImages = pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()
        val users = linkedMapOf<String, String>()
        if (config.separate) {
            for (ref in config.models) {
                val t = separateInputs[ref.key]?.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) users[ref.key] = t
            }
            if (users.isEmpty()) {
                if (!hasImages) return
                for (ref in config.models) users[ref.key] = ""
            }
        } else {
            val t = singleInput?.text?.toString()?.trim().orEmpty()
            if (t.isEmpty() && !hasImages) return
            for (ref in config.models) users[ref.key] = t
        }
        separateInputs.values.forEach { it.setText("") }
        singleInput?.setText("")
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(inputBar.windowToken, 0)
        val imgs = pendingImages.toList()
        pendingImages.clear()
        val files = pendingFiles.toList()
        pendingFiles.clear()
        refreshPreviews()
        startTurn(users, imgs, files)
    }

    private fun systemPromptText(): String {
        if (EconomyPrefs.disableSystemPrompt(this)) return ""
        val persona = if (EconomyPrefs.disablePersonaPrompt(this)) "" else UiPrefs.customPersona(this).trim()
        val bio = if (EconomyPrefs.disableMemoryPrompt(this)) "" else UiPrefs.userBio(this).trim()
        return "You are Yourbit AI, an AI assistant in «Arena» mode: your answer is compared with the answers of other models. " +
            "Answer to the point, by default briefly (2–4 paragraphs).\n" +
            ChatTools.LANGUAGE_RULE + ChatTools.dateRule() +
            (if (persona.isNotBlank()) "• Additional character instructions from the user: $persona\n" else "") +
            (if (bio.isNotBlank()) "• The user told you about themselves (these are their words, not a verified fact): $bio\n" else "") +
            "• When appropriate, format data as markdown tables and code blocks (```language, then the code, then ```).\n" +
            ChatTools.FILE_TOOL_RULE + ChatTools.QUIZ_TOOL_RULE + ChatTools.pollRule() +
            (if (EconomyPrefs.suggestionsEnabled(this)) ChatTools.suggestionsRule() else "") +
            ChatTools.NO_THINK_RULE + ChatTools.LANGUAGE_REMINDER
    }

    private fun composePrompt(text: String, desc: String): String {
        if (desc.isBlank()) return text
        val d = "[Image description]: $desc"
        return if (text.isBlank()) d else "$text\n\n$d"
    }

    private fun fullPrompt(t: ArenaTurn, text: String, historyCap: Int = 0): String {
        val base = composePrompt(text, t.imageContext)
        if (t.fileContext.isBlank()) return base
        val f = if (historyCap > 0) t.fileContext.take(historyCap) else t.fileContext
        return if (base.isBlank()) f else "$base\n\n$f"
    }

    private fun isVisionModel(m: MainActivity.Model): Boolean {
        if (m.isLocal) return false
        if (BuiltinModels.isSystemOne(m.provider, m.id)) return false
        if (m.isVision || m.isVideoVision || m.isMultimodal || ModelAuthorityPrefs.isBuiltinVision(m)) return true
        val id = m.id.lowercase()
        return id.contains("omni") || id.contains("gemini") || id.contains("vision") ||
            id.contains("-vl") || id.contains("vl-") || m.provider == "gemini"
    }

    private suspend fun describeImages(frames: List<String>, userPrompt: String): String {
        val vms = ArenaModels.available(this).filter {
            isVisionModel(it) && (it.serverId.isNotBlank() || ArenaModels.apiKey(this, it).isNotBlank())
        }
        if (vms.isEmpty()) throw Exception("Нет доступных vision-моделей (нужна модель с ключом: Gemini и т.п.) — не могу разобрать изображение.")
        val prompt = if (userPrompt.isBlank()) "Describe in detail what is shown in the picture. No introductions — go straight to the description."
        else "The user sent an image and wrote: \"$userPrompt\"\n\nFirst briefly and to the point describe what is in the picture (what is needed to answer the request). Do not answer the user's question — only describe the image."
        val parts = mutableListOf<String>()
        for ((i, b64) in frames.withIndex()) {
            val content = JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", prompt))
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            }
            val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", content))
            var lastErr: Exception? = null
            var got: String? = null
            for (vm in vms) {
                try {
                    val d = callModel(vm, msgs, 800, null)
                    if (d.isNotBlank()) { got = d.trim(); break }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                }
            }
            if (got == null) throw lastErr ?: Exception("Не удалось проанализировать изображение")
            parts.add(if (frames.size > 1) "Изображение ${i + 1}: $got" else got)
        }
        return parts.joinToString("\n\n")
    }

    private fun startTurn(users: Map<String, String>, imgs: List<ChatImages.Loaded>, files: List<ChatAttach.FileItem> = emptyList()) {
        val answers = linkedMapOf<String, ArenaAnswer>()
        users.keys.forEach { answers[it] = ArenaAnswer() }
        val atts = imgs.map { ArenaAttachment(it.previewPath, it.gifPath) }
        val turn = ArenaTurn(System.currentTimeMillis(), users, answers, users.keys.first(), attachments = atts,
            fileNames = files.map { it.name },
            fileContext = files.joinToString("\n\n") { it.text }.take(60000))
        val histories = users.keys.associateWith { historyFor(it, null) }
        if (turns.isEmpty()) chat.removeAllViews()
        turns.add(turn)
        updateGear()
        chat.addView(buildTurnView(turn, animate = true))
        scrollToEnd()
        ArenaStore.saveTurns(this, turns)

        pending += users.size
        updateSendState()
        val frames = imgs.flatMap { it.frames }
        activeJob = ui.launch {
            if (frames.isNotEmpty()) {
                try {
                    turn.imageContext = describeImages(frames, users.values.firstOrNull { it.isNotBlank() }.orEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось разобрать изображение"
                    for (key in users.keys) {
                        val a = turn.answers[key] ?: continue
                        a.error = true; a.text = msg; a.done = true
                        pending--
                    }
                    refreshTurn(turn)
                    ArenaStore.saveTurns(this@ArenaActivity, turns)
                    updateSendState()
                    return@launch
                }
            }
            val declined = confirmServers(users.keys)
            for ((key, text) in users) {
                launch { sendGate.withPermit { runAnswer(turn, key, fullPrompt(turn, text), histories[key]!!, declined) } }
            }
        }
    }

    private suspend fun confirmServers(keys: Collection<String>): Set<String> {
        val declinedServers = HashSet<String>()
        for (key in keys) {
            val m = ArenaModels.find(this, key)
            val sid = m?.serverId.orEmpty()
            if (sid.isNotBlank() && sid !in declinedServers) {
                val s = ServerStore.find(this, sid)
                if (s != null && !confirmServerHost(s)) declinedServers.add(sid)
            }
        }
        return declinedServers
    }

    private fun regenerate(turn: ArenaTurn, key: String) {
        if (pending > 0) {
            Toast.makeText(this, Loc.t("Дождитесь ответов моделей"), Toast.LENGTH_SHORT).show()
            return
        }
        val ans = turn.answers[key] ?: return
        val text = turn.users[key] ?: return
        val hist = historyFor(key, turn)
        ans.text = ""; ans.error = false; ans.done = false; ans.feedback = ""; ans.files.clear()
        pending++
        updateSendState()
        refreshTurn(turn)
        activeJob = ui.launch {
            val declined = confirmServers(listOf(key))
            runAnswer(turn, key, fullPrompt(turn, text), hist, declined)
        }
    }

    private suspend fun runAnswer(turn: ArenaTurn, key: String, prompt: String, hist: JSONArray, declined: Set<String>) {
        val m = ArenaModels.find(this, key)
        val ans = turn.answers[key] ?: return
        try {
            if (m == null) throw Exception("Модель недоступна: её удалили или отключили сервер.")
            if (m.serverId in declined && m.serverId.isNotBlank())
                throw Exception("Отправка на сервер не подтверждена.")
            val msgs = JSONArray()
            val sys = systemPromptText()
            if (sys.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", sys))
            val searchContext = searchFor(prompt)
            if (searchContext != null) {
                msgs.put(
                    JSONObject().put("role", "system").put(
                        "content",
                        "The client has ALREADY run a web search — don't say that you can't search. " +
                            "Web search results for the user's request (news/Wiki/Fandom/general info). " +
                            "Use them as up-to-date facts if they are relevant; if not, rely on " +
                            "your own knowledge. When you use data from the search you may cite the source in plain words " +
                            "or with a markdown link [text](url):\n\n$searchContext"
                    )
                )
            }
            for (i in 0 until hist.length()) msgs.put(hist.getJSONObject(i))
            msgs.put(JSONObject().put("role", "user").put("content", prompt))
            val raw = callModel(m, msgs, maxTokensFor(m)) { piece ->
                runOnUiThread {
                    if (!ans.done) {
                        ans.text += piece
                        if (turn.selected == key) refreshTurn(turn)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val cleaned = ChatTools.normalizeQuizReply(ChatTools.sanitizeReply(raw))
            if (cleaned.isBlank()) {
                ans.error = true
                ans.text = "Модель вернула пустой ответ"
            } else {
                ans.text = cleaned
                val res = ChatTools.saveFileBlocks(this, cleaned)
                ans.files.putAll(res.saved)
                if (res.created.isNotEmpty())
                    Toast.makeText(this, Loc.t("Файл создан: ${res.created.joinToString(", ")} (Библиотека)"), Toast.LENGTH_LONG).show()
                if (res.skippedMedia.isNotEmpty())
                    Toast.makeText(this, Loc.t("ИИ не может создать медиафайл «${res.skippedMedia.first()}»."), Toast.LENGTH_LONG).show()
                res.errors.firstOrNull()?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamCutException) {
            ans.text = if (e.partialText.isBlank()) (e.message ?: "Поток ответа оборван")
            else e.partialText + "\n\n⚠ Поток ответа оборван"
            ans.error = e.partialText.isBlank()
        } catch (e: Exception) {
            ans.error = true
            ans.text = if (e is ServerException) e.error.display()
            else e.message?.takeIf { it.isNotBlank() } ?: "Не удалось получить ответ"
        }
        ans.done = true
        pending--
        val curr = turn.answers[turn.selected]
        if (!turn.userPicked && !ans.error && (curr == null || !curr.done || curr.error)) turn.selected = key
        refreshTurn(turn)
        ArenaStore.saveTurns(this, turns)
        updateSendState()
    }

    private val searchCache = HashMap<String, String?>()

    private suspend fun searchFor(prompt: String): String? {
        val q = prompt.trim()
        if (q.isEmpty() || !PluginsPrefs.webSearchEnabled(this) || !ChatTools.looksLikeSearchRequest(q)) return null
        synchronized(searchCache) { if (searchCache.containsKey(q)) return searchCache[q] }
        val ctx = try {
            val results = ApiClient.webSearch(q).let { r ->
                if (EconomyPrefs.shortSearch(this)) r.take(EconomyPrefs.SHORT_SEARCH_RESULTS) else r
            }
            if (results.isEmpty()) null else results.joinToString("\n\n") { r ->
                "• [${r.source}] ${r.title}: ${r.snippet}\nSource: ${r.url}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        synchronized(searchCache) { searchCache[q] = ctx }
        return ctx
    }

    private fun historyFor(key: String, upTo: ArenaTurn?): JSONArray {
        val out = JSONArray()
        val src = if (upTo == null) turns else turns.takeWhile { it !== upTo }
        for (t in src.takeLast(8)) {
            val u = t.users[key] ?: continue
            val a = t.answers[key] ?: continue
            if (!a.done || a.error || a.text.isBlank()) continue
            val uc = fullPrompt(t, u, historyCap = 6000)
            if (uc.isBlank()) continue
            out.put(JSONObject().put("role", "user").put("content", uc))
            out.put(JSONObject().put("role", "assistant").put("content", a.text))
        }
        return out
    }

    private fun maxTokensFor(m: MainActivity.Model): Int {
        val manual = if (m.customProviderUrl.isNotBlank())
            ModelsStore.load(this).firstOrNull { it.id == m.id && it.providerUrl == m.customProviderUrl }?.tokenLimit ?: 0
        else if (m.badge == "USR")
            ModelsStore.load(this).firstOrNull { it.id == m.id && it.providerUrl == ModelsStore.builtinMarker(m.provider) }?.tokenLimit ?: 0
        else 0
        return EconomyPrefs.resolveMaxTokens(this, 2800, manual)
    }

    private suspend fun callModel(m: MainActivity.Model, msgs: JSONArray, maxTokens: Int, onDelta: ((String) -> Unit)?): String {
        if (m.serverId.isNotBlank()) {
            val server = ServerStore.find(this, m.serverId)
                ?: throw Exception("Сервер этой модели удалён.")
            if (!server.enabled) throw Exception("Сервер «${server.name}» отключён.")
            val appCtx = applicationContext
            return CustomServerClient.chat(
                server, m.id, msgs, maxTokens,
                onDelta = { piece -> onDelta?.invoke(piece) },
                isOnline = { CustomServerClient.isOnline(appCtx) }
            )
        }
        val apiKey = ArenaModels.apiKey(this, m)
        if (m.customProviderUrl.isNotBlank()) {
            return ApiClient.chatCustom(m.customProviderUrl, m.id, apiKey, msgs, maxTokens)
        }
        if (apiKey.isBlank() && !ApiKeyGate.keyOptional(m.provider)) {
            throw Exception("Для провайдера «${ApiKeyGate.providerLabel(m.provider)}» не задан API-ключ.")
        }
        val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        val cfAccount = getSharedPreferences("nanoai", MODE_PRIVATE).getString("cloudflare_account", "")?.trim().orEmpty()
        return ApiClient.chat(m.provider, m.id, apiKey, msgs, deviceId, cfAccount, maxTokens)
    }

    private suspend fun confirmServerHost(server: CustomServer): Boolean {
        if (server.hostConfirmed) return true
        val host = ServerLogic.hostOf(server.baseUrl) ?: server.baseUrl
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            runOnUiThread {
                if (isFinishing || isDestroyed) { if (cont.isActive) cont.resume(false); return@runOnUiThread }
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                fun answer(v: Boolean) { if (answered.compareAndSet(false, true) && cont.isActive) cont.resume(v) }
                val dialog = styledPanelDialog(
                    title = "Отправить на сервер?",
                    contentView = TextView(this).apply {
                        text = "Сообщения будут отправлены на $host. Продолжить?"
                        setTextColor(Th.fg(0xFFCCCCCC.toInt()))
                        textSize = 14.5f
                    },
                    buttons = listOf(
                        StyledDialogButton("Отмена") { answer(false) },
                        StyledDialogButton("Отправлять") { answer(true) }
                    )
                )
                dialog.setOnDismissListener { answer(false) }
            }
        }
        if (ok) ServerStore.setHostConfirmed(this, server.id, true)
        return ok
    }

    private fun scrollToEnd() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showConfig() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.Dialog? = null

        content.addView(TextView(this).apply {
            text = "Модели для сравнения (${config.models.size}/${ArenaStore.MAX_MODELS})"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        if (config.models.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "Пока пусто. Добавьте минимум две модели."
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 13f
                setPadding(0, 0, 0, dp(6))
            })
        }
        for (ref in config.models) {
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(TextView(this@ArenaActivity).apply {
                    text = ref.name
                    setTextColor(Th.fg(0xFFE8E8E8.toInt()))
                    textSize = 14.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@ArenaActivity).apply {
                    text = Ico.glyph(context, R.drawable.ic_close, 14f, Th.fg(0xFFE57373.toInt()))
                    setTextColor(Th.fg(0xFFE57373.toInt()))
                    textSize = 17f
                    setPadding(dp(14), dp(6), dp(6), dp(6))
                    setOnClickListener {
                        config = config.copy(models = config.models.filter { it.key != ref.key })
                        ArenaStore.saveConfig(this@ArenaActivity, config)
                        dlg?.dismiss()
                        rebuildInputBar(); renderAll(); showConfig()
                    }
                })
            })
        }
        if (config.models.size < ArenaStore.MAX_MODELS) {
            content.addView(pillButton("+ Добавить модель") {
                dlg?.dismiss()
                pickModel()
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })
        }

        content.addView(android.widget.Switch(this).apply {
            text = "Своё сообщение для каждой модели"
            isChecked = config.separate
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14f
            setPadding(0, dp(16), 0, dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, checked ->
                config = config.copy(separate = checked)
                ArenaStore.saveConfig(this@ArenaActivity, config)
                rebuildInputBar()
            }
        })
        content.addView(TextView(this).apply {
            text = "Выкл — одно сообщение уходит всем моделям. Вкл — у каждой модели своё поле; пустые поля пропускаются."
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12f
        })

        if (turns.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = "Очистить диалог"
                setTextColor(Th.fg(0xFFE57373.toInt()))
                textSize = 14.5f
                setPadding(0, dp(18), 0, dp(4))
                setOnClickListener {
                    dlg?.dismiss()
                    styledConfirmDialog("Очистить Арену?", "Все сообщения и ответы Арены будут удалены.", "Очистить", destructive = true) {
                        turns.clear()
                        ArenaStore.clearTurns(this@ArenaActivity)
                        renderAll()
                    }
                }
            })
        }

        val scrollContent = ScrollView(this).apply { addView(content) }
        dlg = styledPanelDialog("Настройка Арены", scrollContent, listOf(StyledDialogButton("Готово") {}))
    }

    private fun pickModel() {
        val chosen = config.models.map { it.key }.toSet()
        val options = ArenaModels.available(this).filter { ArenaModels.keyOf(it) !in chosen }
        if (options.isEmpty()) {
            Toast.makeText(this, Loc.t("Нет доступных моделей. Добавьте ключи или сервер в настройках."), Toast.LENGTH_LONG).show()
            return
        }
        showModelListPanel(
            title = "Добавить модель",
            subtitle = "Выберите модель для сравнения",
            models = options,
            noKey = { m -> m.serverId.isBlank() && ArenaModels.apiKey(this, m).isBlank() }
        ) { m ->
            config = config.copy(models = config.models + ArenaModelRef(ArenaModels.keyOf(m), m.name))
            ArenaStore.saveConfig(this, config)
            rebuildInputBar()
            renderAll()
            showConfig()
        }
    }
}
