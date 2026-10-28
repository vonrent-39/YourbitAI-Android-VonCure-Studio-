package com.youraicqa.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

class AgentActivity : NanoBaseActivity() {

    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private lateinit var web: WebView
    private lateinit var webFrame: FrameLayout
    private lateinit var urlField: EditText
    private lateinit var loadBar: ProgressBar
    private lateinit var logBox: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: ImageView
    private lateinit var modelChip: TextView
    private lateinit var workingBadge: TextView
    private lateinit var panel: LinearLayout

    private var model: MainActivity.Model? = null
    private var running = false
    private var pageLoading = false
    private var panelMode = 1
    private var pendingAnswer: CancellableContinuation<String>? = null

    private var chatId = System.currentTimeMillis().toString()
    private val entries = ArrayList<Pair<String, String>>()
    private var restoring = false

    private val pendingFiles = ArrayList<ChatAttach.FileItem>()
    private val pendingImages = ArrayList<String>()
    private lateinit var attachScroll: android.widget.HorizontalScrollView
    private lateinit var attachRow: LinearLayout
    private val PICK_FILE_REQ = 7611

    private class Observation(
        val json: JSONObject,
        val brief: String,
        val full: String,
        val shotB64: String?,
        val shotW: Int,
        val elements: Map<Int, JSONObject>
    )

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun prefs() = getSharedPreferences("nanoai", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)
        AchievementsStore.onTabOpened(this, "agent")
        setContentView(buildUi())
        restoreModel()
        updateModelChip()
        applyPanelMode()
        updateRunUi()
        web.loadDataWithBaseURL("about:blank", HOME_HTML, "text/html", "UTF-8", null)
        val last = AgentChatStore.list(this).firstOrNull()
        if (last != null && AgentChatStore.load(this, last.id) != null) restoreChat(last.id)
        else showIntro()
    }

    private fun showIntro() {
        addNotice(
            "Опишите задачу — модель сама откроет нужные страницы во встроенном браузере, будет нажимать, печатать, " +
                "прокручивать и присылать скриншоты. Браузером можно пользоваться и вручную. Остановить агента можно кнопкой ■. " +
                "Можно прикреплять файлы (скрепка), чат сохраняется автоматически."
        )
    }

    private fun rec(kind: String, text: String) {
        if (restoring || text.isBlank()) return
        entries.add(kind to text)
        if (entries.any { it.first == "user" }) {
            val title = entries.first { it.first == "user" }.second.replace('\n', ' ').take(48)
            AgentChatStore.save(this, chatId, title, entries)
        }
    }

    private fun restoreChat(id: String) {
        val data = AgentChatStore.load(this, id) ?: return
        restoring = true
        try {
            logBox.removeAllViews()
            entries.clear()
            chatId = id
            for ((k, t) in data) {
                entries.add(k to t)
                when (k) {
                    "user" -> addUser(t)
                    "thought" -> addThought(t)
                    "action" -> addAction(t.removePrefix("▶ "))
                    "error" -> addError(t.removePrefix("⚠ "))
                    "ask" -> addAsk(t.removePrefix("❓ "))
                    "done" -> addDone(t.removePrefix("✅ "))
                }
            }
        } finally {
            restoring = false
        }
        scrollLogDown()
    }

    private fun newChat() {
        if (running) { Toast.makeText(this, Loc.t("Сначала остановите агента"), Toast.LENGTH_SHORT).show(); return }
        logBox.removeAllViews()
        entries.clear()
        chatId = System.currentTimeMillis().toString()
        pendingFiles.clear(); pendingImages.clear(); refreshAttachRow()
        showIntro()
    }

    private fun showSavedChats() {
        val metas = AgentChatStore.list(this)
        if (metas.isEmpty()) { Toast.makeText(this, Loc.t("Сохранённых чатов пока нет"), Toast.LENGTH_SHORT).show(); return }
        val fmt = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
        val items = metas.map { "${it.title}  ·  ${fmt.format(java.util.Date(it.ts))}" } + listOf(Loc.t("Удалить все сохранённые чаты"))
        styledListDialog(Loc.t("Чаты агента"), items) { idx ->
            if (idx == metas.size) {
                styledConfirmDialog("Удалить все чаты агента?", "Сохранённые чаты будут удалены без возможности восстановления.", "Удалить", destructive = true) {
                    AgentChatStore.deleteAll(this)
                    newChat()
                }
            } else if (!running) {
                restoreChat(metas[idx].id)
            } else Toast.makeText(this, Loc.t("Сначала остановите агента"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun chatAsText(): String = entries.joinToString("\n\n") { (k, t) ->
        when (k) {
            "user" -> "👤 $t"
            "done" -> "✅ $t"
            "ask" -> "❓ $t"
            "error" -> "⚠ $t"
            "action" -> "▶ $t"
            else -> t
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text))
        Toast.makeText(this, Loc.t("Скопировано"), Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
            }, "Поделиться"))
        } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_FILE_REQ || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            if (ChatAttach.isImage(this, uri)) {
                val m = model
                if (m == null || !isVision(m)) {
                    Toast.makeText(this, Loc.t("Модель агента не умеет видеть картинки — выберите vision-модель или включите Vision в «Авторитетности»"), Toast.LENGTH_LONG).show()
                    return
                }
                if (pendingImages.size >= 3) { Toast.makeText(this, Loc.t("Максимум 3 изображения"), Toast.LENGTH_SHORT).show(); return }
                pendingImages.addAll(ChatImages.load(this, uri).frames.take(1))
            } else {
                if (pendingFiles.size >= 5) { Toast.makeText(this, Loc.t("Максимум 5 файлов"), Toast.LENGTH_SHORT).show(); return }
                pendingFiles.add(ChatAttach.readFile(this, uri))
            }
            refreshAttachRow()
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Не удалось открыть файл: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshAttachRow() {
        attachRow.removeAllViews()
        attachScroll.visibility = if (pendingFiles.isEmpty() && pendingImages.isEmpty()) View.GONE else View.VISIBLE
        fun chip(iconRes: Int, label: String, onRemove: () -> Unit) = TextView(this).apply {
            text = label; textSize = 12f; maxLines = 1; maxWidth = dp(220)
            gravity = Gravity.CENTER_VERTICAL
            val tintC = Th.fg(0xFFE8E8E8.toInt())
            setCompoundDrawablesRelative(Ico.drawable(this@AgentActivity, iconRes, 14f, tintC), null, Ico.drawable(this@AgentActivity, R.drawable.ic_close, 12f, tintC), null)
            compoundDrawablePadding = dp(8)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(Th.fg(0xFFE8E8E8.toInt()))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { setColor(Th.bg(0xFF2A2A32.toInt())); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            setOnClickListener { onRemove() }
        }
        for (f in pendingFiles.toList()) attachRow.addView(chip(R.drawable.ic_attach, "${f.name} · ${f.sizeLabel}") { pendingFiles.remove(f); refreshAttachRow() })
        for ((i, img) in pendingImages.toList().withIndex()) attachRow.addView(chip(R.drawable.ic_gallery, "${i + 1}") { pendingImages.remove(img); refreshAttachRow() })
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF0B0B0E.toInt()))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(10), dp(4))
        }
        top.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt())); textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "Агент"; textSize = 19f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        modelChip = TextView(this).apply {
            textSize = 12.5f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Th.fg(0xFFE0E0E0.toInt()))
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1E26.toInt())); cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(6) }
            maxWidth = (resources.displayMetrics.widthPixels * 0.5f).toInt()
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { if (!running) pickModel() }
        }
        top.addView(modelChip)
        top.addView(TextView(this).apply {
            text = "⋯"; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { showMenu() }
        })
        root.addView(top)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        fun navIconBtn(iconRes: Int, onClick: () -> Unit) = ImageView(this).apply {
            ActionIcons.apply(this, iconRes)
            setColorFilter(Th.fg(0xFFE0E0E0.toInt()))
            setPadding(dp(8), dp(9), dp(8), dp(9))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(40))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { onClick() }
        }
        fun navBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFE0E0E0.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(40))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { onClick() }
        }
        bar.addView(navBtn("‹") { if (web.canGoBack()) web.goBack() })
        bar.addView(navBtn("›") { if (web.canGoForward()) web.goForward() })
        bar.addView(navBtn("⟳") { web.reload() })
        urlField = EditText(this).apply {
            hint = "Адрес или поисковый запрос"
            setHintTextColor(Th.fg(0xFF777777.toInt())); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14f; setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1E26.toInt())); cornerRadius = dp(18).toFloat() }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) { openUserUrl(text.toString()); true } else false
            }
        }
        bar.addView(urlField)
        bar.addView(navIconBtn(R.drawable.ic_camera) { manualScreenshot() })
        root.addView(bar)

        loadBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        }
        root.addView(loadBar)

        webFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.WHITE)
        }
        web = buildWebView()
        webFrame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        workingBadge = TextView(this).apply {
            text = "● Агент работает"; textSize = 11.5f; visibility = View.GONE
            setTextColor(0xFF101114.toInt()); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply { setColor(0xE67CFFB2.toInt()); cornerRadius = dp(14).toFloat() }
        }
        webFrame.addView(workingBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { topMargin = dp(8); rightMargin = dp(8) })
        root.addView(webFrame)

        root.addView(TextView(this).apply {
            text = "⌃  чат агента  ⌄"; textSize = 12f; gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFF8A8A92.toInt()))
            setBackgroundColor(Th.bg(0xFF121216.toInt()))
            setPadding(0, dp(7), 0, dp(7))
            setOnClickListener { panelMode = (panelMode + 1) % 3; applyPanelMode() }
        })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF121216.toInt()))
        }
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        logBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        logScroll.addView(logBox)
        panel.addView(logScroll)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        input = EditText(this).apply {
            hint = "Что сделать агенту?"
            setHintTextColor(Th.fg(0xFF777777.toInt())); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14.5f; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1E26.toInt())); cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(ImageView(this).apply {
            ActionIcons.apply(this, R.drawable.ic_attach)
            setColorFilter(Th.fg(0xFFE0E0E0.toInt()))
            setPadding(dp(9), dp(12), dp(9), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(46)).apply { marginEnd = dp(2) }
            applyPressAnimation(PressAnimStyle.POP)
            setOnClickListener {
                if (running && pendingAnswer == null) return@setOnClickListener
                startActivityForResult(Intent.createChooser(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                }, "Файл"), PICK_FILE_REQ)
            }
        }, 0)
        inputRow.addView(input)
        sendBtn = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setImageResource(R.drawable.ic_send_arrow)
            setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_send_button)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) }
            applyPressAnimation(PressAnimStyle.POP)
            setOnClickListener { onSendClicked() }
        }
        inputRow.addView(sendBtn)
        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        attachScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(dp(12), dp(4), dp(12), 0)
            addView(attachRow)
        }
        panel.addView(attachScroll)
        panel.addView(inputRow)
        root.addView(panel)
        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setGeolocationEnabled(false)
        settings.textZoom = 100
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this@AgentActivity, Loc.t("Загрузки файлов в браузере агента отключены"), Toast.LENGTH_SHORT).show()
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageLoading = true
                if (url != null && !url.startsWith("about:") && !urlField.hasFocus()) urlField.setText(url)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                pageLoading = false
                if (url != null && !url.startsWith("about:") && !urlField.hasFocus()) urlField.setText(url)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme?.lowercase()
                return !(s == "http" || s == "https" || s == "about")
            }
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                addNotice("Ошибка сертификата сайта — загрузка отменена.")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadBar.progress = newProgress
                loadBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val temp = WebView(this@AgentActivity)
                var handled = false
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        if (!handled) { handled = true; loadIfHttp(r.url.toString()); v.post { try { v.destroy() } catch (_: Exception) {} } }
                        return true
                    }
                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (!handled && url != null && url.startsWith("http")) {
                            handled = true; loadIfHttp(url); v.stopLoading(); v.post { try { v.destroy() } catch (_: Exception) {} }
                        }
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                addNotice(Loc.t("Страница: " + (message ?: "").take(200))); result?.confirm(); return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                addNotice(Loc.t("Страница спрашивает: " + (message ?: "").take(200) + " — отвечено «ОК»")); result?.confirm(); return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                result?.cancel(); return true
            }
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
    }

    private fun loadIfHttp(url: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) web.loadUrl(url)
    }

    private fun openUserUrl(raw: String) {
        val u = AgentProtocol.normalizeUrl(raw)
        if (u == null) {
            Toast.makeText(this, Loc.t("Этот тип адреса не поддерживается"), Toast.LENGTH_SHORT).show()
            return
        }
        urlField.clearFocus()
        hideKeyboard()
        web.loadUrl(u)
    }

    private fun hideKeyboard() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(webFrame.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun applyPanelMode() {
        val h = resources.displayMetrics.heightPixels
        when (panelMode) {
            0 -> { logScroll.visibility = View.GONE; panel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            1 -> { logScroll.visibility = View.VISIBLE; panel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * 0.34f).toInt()) }
            else -> { logScroll.visibility = View.VISIBLE; panel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (h * 0.62f).toInt()) }
        }
        panel.requestLayout()
    }

    private fun showMenu() {
        val items = listOf(
            "Выбрать модель агента",
            "Новый чат",
            "Сохранённые чаты",
            "Скопировать весь чат",
            "Поделиться чатом",
            "Удалить этот чат",
            "Очистить данные браузера (куки, кэш)",
            "Открыть страницу во внешнем браузере"
        )
        styledListDialog("Агент", items.map { Loc.t(it) }) { idx ->
            when (idx) {
                0 -> if (!running) pickModel() else Toast.makeText(this, Loc.t("Сначала остановите агента"), Toast.LENGTH_SHORT).show()
                1 -> newChat()
                2 -> showSavedChats()
                3 -> if (entries.isEmpty()) Toast.makeText(this, Loc.t("Чат пуст"), Toast.LENGTH_SHORT).show() else copyToClipboard(chatAsText())
                4 -> if (entries.isEmpty()) Toast.makeText(this, Loc.t("Чат пуст"), Toast.LENGTH_SHORT).show() else shareText(chatAsText())
                5 -> if (!running) { AgentChatStore.delete(this, chatId); newChat() } else Toast.makeText(this, Loc.t("Сначала остановите агента"), Toast.LENGTH_SHORT).show()
                6 -> {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    WebStorage.getInstance().deleteAllData()
                    web.clearCache(true); web.clearHistory()
                    Toast.makeText(this, Loc.t("Данные браузера очищены"), Toast.LENGTH_SHORT).show()
                }
                7 -> {
                    val u = web.url
                    if (u != null && u.startsWith("http")) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun isVision(m: MainActivity.Model) = ModelAuthorityPrefs.isVisionCapable(m)

    private fun hasCredentials(m: MainActivity.Model): Boolean =
        m.serverId.isNotBlank() || m.customProviderUrl.isNotBlank() || ArenaModels.apiKey(this, m).isNotBlank()

    private fun restoreModel() {
        val all = ArenaModels.available(this)
        val saved = prefs().getString("agent_model", null)
        model = all.firstOrNull { ArenaModels.keyOf(it) == saved && hasCredentials(it) }
            ?: all.firstOrNull { isVision(it) && hasCredentials(it) }
            ?: all.firstOrNull { hasCredentials(it) }
    }

    private fun updateModelChip() {
        val m = model
        modelChip.text = if (m == null) "Выбрать модель" else m.name
        if (m != null && isVision(m)) Ico.start(modelChip, R.drawable.ic_eye, 15f, Th.fg(0xFFE0E0E0.toInt()), 6f)
        else modelChip.setCompoundDrawablesRelative(null, null, null, null)
        Loc.translateTree(modelChip)
    }

    private fun pickModel() {
        val opts = ArenaModels.available(this).sortedByDescending { isVision(it) }
        if (opts.isEmpty()) {
            Toast.makeText(this, Loc.t("Нет доступных моделей. Добавьте ключи или сервер в настройках."), Toast.LENGTH_LONG).show()
            return
        }
        showModelListPanel(
            title = "Модель агента",
            subtitle = "Лучше всего подходят модели с распознаванием изображений (со значком глаза)",
            models = opts,
            noKey = { m -> !hasCredentials(m) }
        ) { m ->
            model = m
            prefs().edit().putString("agent_model", ArenaModels.keyOf(m)).apply()
            updateModelChip()
        }
    }

    private fun scrollLogDown() { logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) } }

    private fun addRow(v: View) {
        logBox.addView(v)
        Loc.translateTree(v)
        scrollLogDown()
    }

    private fun bubble(text: String, bg: Int, fg: Int, alignEnd: Boolean = false, mono: Boolean = false, size: Float = 13.5f, icon: Int = 0): TextView =
        TextView(this).apply {
            this.text = if (icon != 0) Ico.prefixed(this@AgentActivity, icon, size + 2f, Th.fg(fg), Loc.t(text)) else text
            textSize = size
            setTextColor(Th.fg(fg))
            if (mono) typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(Th.bg(bg)); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4); bottomMargin = dp(2)
                gravity = if (alignEnd) Gravity.END else Gravity.START
                if (alignEnd) marginStart = dp(40) else marginEnd = dp(24)
            }
        }

    private fun actionIcon(icon: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        ActionIcons.apply(this, icon); setColorFilter(Th.fg(0xFFCCCCCC.toInt()))
        setPadding(dp(7), dp(7), dp(7), dp(7))
        background = androidx.core.content.ContextCompat.getDrawable(this@AgentActivity, R.drawable.bg_copy_btn)
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(9) }
        applyPressAnimation(PressAnimStyle.POP)
        setOnClickListener { onClick() }
    }

    private fun withActions(b: View, text: String, alignEnd: Boolean, extra: List<View> = emptyList()): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(b)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (alignEnd) Gravity.END else Gravity.START
            setPadding(dp(4), dp(1), dp(4), dp(3))
        }
        bar.addView(actionIcon(R.drawable.ic_copy, "Копировать") { copyToClipboard(text) })
        bar.addView(actionIcon(R.drawable.ic_share, "Поделиться") { shareText(text) })
        extra.forEach { bar.addView(it) }
        (bar.getChildAt(bar.childCount - 1)?.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
        col.addView(bar)
        return col
    }

    private fun addUser(t: String) {
        rec("user", t)
        val extra = listOf(
            actionIcon(R.drawable.ic_pencil_asset, "Изменить") { input.setText(t); input.setSelection(input.text.length); input.requestFocus() },
            actionIcon(R.drawable.ic_regenerate, "Повторить") {
                if (running) Toast.makeText(this, Loc.t("Сначала остановите агента"), Toast.LENGTH_SHORT).show() else startTask(t, repeat = true)
            }
        )
        addRow(withActions(bubble(t, 0xFF2D2A4A.toInt(), 0xFFFFFFFF.toInt(), alignEnd = true), t, true, extra))
    }
    private fun addThought(t: String) {
        if (t.isNotBlank()) { rec("thought", t); addRow(bubble(t, 0xFF1A1A20.toInt(), 0xFF9A9AA5.toInt(), size = 12.5f)) }
    }
    private fun addAction(t: String) { rec("action", "▶ $t"); addRow(bubble(t, 0xFF16241C.toInt(), 0xFF7CFFB2.toInt(), mono = true, size = 12f, icon = R.drawable.ic_play)) }
    private fun addNotice(t: String) = addRow(bubble(t, 0xFF1A1A20.toInt(), 0xFF8A8A92.toInt(), size = 12f))
    private fun addError(t: String) { rec("error", "⚠ $t"); addRow(withActions(bubble(t, 0xFF3A1E1E.toInt(), 0xFFFFB4AB.toInt(), icon = R.drawable.ic_warning), t, false)) }
    private fun addAsk(t: String) { rec("ask", "❓ $t"); addRow(withActions(bubble(t, 0xFF3A3218.toInt(), 0xFFFFE082.toInt(), icon = R.drawable.ic_help), t, false)) }
    private fun addDone(t: String) { rec("done", "✅ $t"); addRow(withActions(bubble(t, 0xFF1B3A24.toInt(), 0xFFFFFFFF.toInt(), icon = R.drawable.ic_check), t, false)) }

    private fun addContinueRow(text: String, onContinue: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(bubble(text, 0xFF3A3218.toInt(), 0xFFFFE082.toInt(), icon = R.drawable.ic_hand))
        row.addView(TextView(this).apply {
            this.text = "Продолжить"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { setColor(Th.bg(0xFF43A047.toInt())); cornerRadius = dp(16).toFloat() }
            setPadding(dp(18), dp(9), dp(18), dp(9))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { isEnabled = false; alpha = 0.5f; onContinue() }
        })
        addRow(row)
    }

    private fun addScreenshotRow(file: File, caption: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1A1A20.toInt())); cornerRadius = dp(14).toFloat() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4); bottomMargin = dp(2); marginEnd = dp(24) }
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bmp = try { BitmapFactory.decodeFile(file.absolutePath, opts) } catch (_: Exception) { null }
        if (bmp != null) {
            box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                maxHeight = dp(240)
                layoutParams = LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { shareFile(file) }
            })
        }
        box.addView(TextView(this).apply {
            text = caption.ifBlank { Loc.t("Скриншот") } + "  ·  " + Loc.t("нажмите, чтобы поделиться")
            gravity = Gravity.CENTER_VERTICAL
            Ico.start(this, R.drawable.ic_camera, 14f, Th.fg(0xFF9A9AA5.toInt()), 6f)
            textSize = 11.5f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(dp(2), dp(6), dp(2), 0)
            setOnClickListener { shareFile(file) }
        })
        addRow(box)
    }

    private fun shareFile(f: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, null))
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRunUi() {
        val awaiting = pendingAnswer != null
        workingBadge.visibility = if (running) View.VISIBLE else View.GONE
        val stopMode = running && !awaiting
        sendBtn.setImageResource(if (stopMode) R.drawable.ic_stop_square else R.drawable.ic_send_arrow)
        sendBtn.setColorFilter(0xFFFFFFFF.toInt())
        sendBtn.setBackgroundResource(if (stopMode) R.drawable.bg_send_stop else R.drawable.bg_send_button)
        sendBtn.contentDescription = if (stopMode) "Остановить" else "Отправить"
        input.hint = if (awaiting) "Ваш ответ агенту…" else "Что сделать агенту?"
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onSendClicked() {
        val text = input.text.toString().trim()
        val pa = pendingAnswer
        when {
            pa != null -> {
                if (text.isEmpty()) return
                input.text.clear()
                addUser(text)
                pendingAnswer = null
                pa.resume(text)
                updateRunUi()
            }
            running -> { job?.cancel() }
            text.isNotEmpty() || pendingFiles.isNotEmpty() || pendingImages.isNotEmpty() -> {
                input.text.clear(); hideKeyboard(); startTask(text.ifBlank { Loc.t("Изучи прикреплённые файлы и выполни задачу по ним.") })
            }
        }
    }

    private fun conversationContext(): String {
        val past = entries.filter { it.first == "user" || it.first == "done" || it.first == "ask" }.takeLast(8)
        if (past.isEmpty()) return ""
        return "Earlier in this conversation:\n" + past.joinToString("\n") { (k, t) ->
            (if (k == "user") "User: " else "Agent: ") + t.removePrefix("✅ ").removePrefix("❓ ").take(400)
        } + "\n\n"
    }

    private fun startTask(task: String, repeat: Boolean = false) {
        val m = model
        if (m == null || !hasCredentials(m)) {
            addError("Не выбрана модель или для неё нет ключа. Нажмите на название модели вверху, чтобы выбрать.")
            pickModel()
            return
        }
        running = true
        updateRunUi()
        val files = pendingFiles.toList()
        val images = pendingImages.toList()
        pendingFiles.clear(); pendingImages.clear(); refreshAttachRow()
        val ctx = conversationContext()
        val shown = if (files.isEmpty() && images.isEmpty()) task
            else task + "\n" + (files.map { "📎 ${it.name}" } + images.indices.map { "🖼 ${it + 1}" }).joinToString("  ")
        addUser(shown)
        val fullTask = ctx + task + (if (files.isEmpty()) "" else "\n\n" + files.joinToString("\n\n") { it.text })
        job = ui.launch {
            try {
                runAgent(m, fullTask, images)
            } catch (e: CancellationException) {
                addNotice("⏹ Агент остановлен.")
            } catch (e: Exception) {
                addError(e.message ?: e.toString())
            } finally {
                running = false
                pendingAnswer = null
                updateRunUi()
            }
        }
    }

    private suspend fun runAgent(m: MainActivity.Model, task: String, images: List<String> = emptyList()) {
        val visionModel = isVision(m)
        val vision = visionModel && !EconomyPrefs.agentTextOnly(this)
        val maxSteps = EconomyPrefs.agentMaxSteps(this)
        val system = AgentProtocol.systemPrompt(vision, task, maxSteps)
        val past = ArrayList<Pair<String, String>>()
        val ladder = AgentLadder()
        if (EconomyPrefs.capReasoning(this)) ladder.effort = "low"
        var lastResult = "Browser ready."
        var parseFails = 0
        for (step in 1..maxSteps) {
            val obs = observe(vision)
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "system").put("content", system))
            for ((o, a) in past.takeLast(6)) {
                msgs.put(JSONObject().put("role", "user").put("content", o))
                msgs.put(JSONObject().put("role", "assistant").put("content", a))
            }
            val textPart = "Step $step of $maxSteps. Result of your previous action: $lastResult\n\n" + obs.full
            val userImgs = if (step == 1 && visionModel) images else emptyList()
            val content: Any = if (obs.shotB64 != null || userImgs.isNotEmpty()) {
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", textPart))
                    for (img in userImgs) put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$img")))
                    val shot = obs.shotB64
                    if (shot != null) put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$shot")))
                }
            } else textPart
            msgs.put(JSONObject().put("role", "user").put("content", content))

            workingBadge.text = Loc.t("● Шаг $step/$maxSteps: думаю…")
            var emptyReason = ""
            val reply: String = try {
                AgentModelClient.chatRobust(this, m, msgs, 2000, ladder)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentEmptyReply) {
                emptyReason = e.reason
                ""
            } catch (e: ServerException) {
                val extra = if (e.error.code == "E03") "\nМодель: " + m.name + " (" + m.id + ")" else ""
                throw Exception(e.error.display() + extra, e)
            }
            val action = if (reply.isBlank()) null else AgentProtocol.parse(reply)
            if (action == null) {
                parseFails++
                val blank = reply.isBlank()
                if (parseFails >= (if (blank) 2 else 3)) {
                    addError(
                        if (blank) emptyReason.ifBlank { "Модель не вернула ответ. Попробуйте другую модель или проверьте настройки сервера." }
                        else "Модель не смогла вернуть корректное действие (JSON). Начало её ответа: «" +
                            reply.replace(Regex("\\s+"), " ").trim().take(120) + "». Попробуйте другую модель."
                    )
                    return
                }
                lastResult = if (blank) "Your previous reply was EMPTY. Reply with ONE JSON object only, without long reasoning."
                else "Your reply was not a valid JSON action object. Reply with ONE JSON object only."
                continue
            }
            parseFails = 0
            val historyReply = reply.replace(Regex("(?s)<think(ing)?>.*?</think(ing)?>"), "").trim().take(900)
            past.add(obs.brief to historyReply.ifBlank { "{\"action\":\"" + action.type + "\"}" })
            addThought(action.thought)
            val label = obs.elements[action.id]?.optString("text")?.takeIf { it.isNotBlank() }
            addAction(Loc.t(AgentProtocol.describe(action, label)))
            workingBadge.text = Loc.t("● Шаг $step/$maxSteps")

            if (action.type == "done") {
                addDone(action.message.ifBlank { "Задача выполнена." })
                return
            }
            lastResult = try {
                execute(action, obs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "error: " + (e.message ?: e.toString())
            }
            if (lastResult.startsWith("error")) addNotice(lastResult.take(200))
        }
        addNotice(Loc.t("Достигнут лимит шагов ($maxSteps). Напишите «продолжай», чтобы агент продолжил с текущей страницы."))
    }

    private suspend fun execute(a: AgentAction, obs: Observation): String {
        val el = obs.elements[a.id]
        when (a.type) {
            "navigate" -> {
                val u = AgentProtocol.normalizeUrl(a.url) ?: return "error: this URL scheme is not allowed"
                val host = try { Uri.parse(u).host.orEmpty() } catch (_: Exception) { "" }
                val priv = withContext(Dispatchers.IO) { AgentProtocol.isPrivateHost(host) }
                if (priv) return "error: navigation to local-network addresses is blocked"
                web.loadUrl(u)
                settle(true)
                return "navigated to ${web.url}"
            }
            "tap" -> {
                if (el == null) return "error: no element with id ${a.id} on the current page; use ids from the latest list"
                val label = el.optString("text") + " " + el.optString("hint")
                if (AgentProtocol.isSensitiveLabel(label)) {
                    val ok = confirmAction(
                        "Подтвердите действие",
                        Loc.t("Агент хочет нажать «" + el.optString("text").take(80) + "» на странице " + (web.url ?: "") + ". Разрешить?")
                    )
                    if (!ok) return "The user DENIED this action. Do not repeat it; ask the user what to do instead."
                }
                return tapElement(a.id)
            }
            "tap_xy" -> {
                if (a.x < 0 || a.y < 0 || obs.shotW <= 0) return "error: tap_xy needs x and y in screenshot pixels"
                val k = web.width.toFloat() / obs.shotW
                dispatchTap(a.x * k, a.y * k)
                settle(false)
                return "tapped at (${a.x}, ${a.y})"
            }
            "type" -> {
                if (el == null) return "error: no element with id ${a.id}"
                if (AgentProtocol.isSecretField(el.optString("type"), el.optString("hint"))) {
                    awaitContinue("Это поле для секретных данных (пароль/карта/код). Введите их сами прямо в браузере и нажмите «Продолжить».")
                    return "The user filled in the secret field himself. Continue with the next step."
                }
                val r = evalJs(AgentJs.setValue(a.id, JSONObject.quote(a.text), a.submit))
                if (r != "ok") return "error: $r"
                hideKeyboard()
                settle(a.submit)
                return "typed into [${a.id}]" + if (a.submit) " and submitted" else ""
            }
            "select" -> {
                if (el == null) return "error: no element with id ${a.id}"
                val r = evalJs(AgentJs.select(a.id, JSONObject.quote(a.value)))
                settle(false)
                return r
            }
            "scroll" -> {
                val r = evalJs(AgentJs.scroll(a.direction))
                delay(350)
                return "scrolled: $r"
            }
            "key" -> {
                val r = evalJs(AgentJs.key(JSONObject.quote(a.key.ifBlank { "Enter" })))
                settle(a.key.equals("Enter", true))
                return r
            }
            "back" -> { if (!web.canGoBack()) return "error: no history to go back"; web.goBack(); settle(true); return "went back to ${web.url}" }
            "forward" -> { if (!web.canGoForward()) return "error: no forward history"; web.goForward(); settle(true); return "went forward to ${web.url}" }
            "reload" -> { web.reload(); settle(true); return "reloaded" }
            "wait" -> { delay(a.ms.coerceIn(300, 8000).toLong()); return "waited" }
            "read" -> {
                val r = evalJs(AgentJs.readText(a.offset))
                return try {
                    val j = JSONObject(r)
                    "page text from ${a.offset} (total ${j.optInt("total")} chars):\n" + j.optString("text")
                } catch (_: Exception) { "error: could not read the page" }
            }
            "screenshot" -> {
                val f = saveScreenshot() ?: return "error: could not capture a screenshot"
                addScreenshotRow(f, a.note)
                return "screenshot was sent to the user"
            }
            "ask_user" -> {
                addAsk(a.message.ifBlank { "Нужна ваша помощь." })
                val ans = awaitAnswer()
                return "The user answered: $ans"
            }
        }
        return "error: unknown action"
    }

    private suspend fun observe(vision: Boolean): Observation {
        settle(false)
        val textChars = EconomyPrefs.agentPageTextChars(this)
        val raw = evalJs(AgentJs.observeJs(EconomyPrefs.agentMaxElements(this), textChars))
        val j = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("err", "page not readable") }
        val els = LinkedHashMap<Int, JSONObject>()
        val arr = j.optJSONArray("els") ?: JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { els[it.optInt("id")] = it }

        val sb = StringBuilder()
        sb.append("URL: ").append(j.optString("url", web.url ?: "about:blank")).append('\n')
        sb.append("Title: ").append(j.optString("title")).append('\n')
        if (j.has("sh")) sb.append("Scroll: ").append(j.optInt("sy")).append(" of ").append(j.optInt("sh")).append(" px (viewport ").append(j.optInt("vh")).append(" px)\n")
        if (j.has("err")) sb.append("Page error: ").append(j.optString("err")).append('\n')
        val elText = StringBuilder()
        for ((id, e) in els) {
            val tag = e.optString("tag"); val type = e.optString("type")
            val kind = when {
                tag == "a" -> "link"
                tag == "input" || tag == "textarea" -> "input($type)"
                tag == "select" -> "select"
                else -> tag
            }
            elText.append('[').append(id).append("] ").append(kind)
            if (e.optString("text").isNotBlank()) elText.append(" \"").append(e.optString("text")).append('"')
            if (e.optString("val").isNotBlank()) elText.append(" value=\"").append(e.optString("val")).append('"')
            if (e.optBoolean("chk")) elText.append(" [checked]")
            if (tag == "a" && e.optString("href").isNotBlank()) elText.append(" -> ").append(e.optString("href").take(70))
            elText.append('\n')
        }
        val brief = sb.toString() + "Interactive elements:\n" + elText.toString().lines().take(25).joinToString("\n")
        sb.append("Interactive elements (visible now):\n").append(if (elText.isEmpty()) "(none)\n" else elText.toString())
        sb.append("\nPage text (first ").append(textChars).append(" chars of ").append(j.optInt("textLen")).append("):\n").append(j.optString("text"))

        var b64: String? = null
        var shotW = 0
        val bmp = if (vision) captureWeb() else null
        if (bmp != null) {
            val maxW = EconomyPrefs.agentShotWidth(this)
            val scaled = if (bmp.width > maxW) Bitmap.createScaledBitmap(bmp, maxW, (bmp.height * maxW.toFloat() / bmp.width).toInt().coerceAtLeast(1), true) else bmp
            shotW = scaled.width
            if (vision) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, EconomyPrefs.agentShotQuality(this), out)
                b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                sb.append("\n\n(Screenshot attached: ").append(scaled.width).append('x').append(scaled.height).append(" px; tap_xy uses these pixel coordinates.)")
            }
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
        }
        return Observation(j, brief, sb.toString(), b64, shotW, els)
    }

    private suspend fun evalJs(js: String): String = suspendCancellableCoroutine { cont ->
        try {
            web.evaluateJavascript(js) { value ->
                if (!cont.isActive) return@evaluateJavascript
                val s = if (value == null || value == "null") "" else try {
                    (JSONTokener(value).nextValue() as? String) ?: value
                } catch (_: Exception) { value }
                cont.resume(s)
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume("error: " + (e.message ?: ""))
        }
    }

    private suspend fun settle(afterNavigation: Boolean) {
        if (afterNavigation) delay(450) else delay(250)
        var waited = 0
        while (pageLoading && waited < 10000) { delay(150); waited += 150 }
        delay(if (afterNavigation) 500 else 200)
    }

    private suspend fun tapElement(id: Int): String {
        val raw = evalJs(AgentJs.locate(id))
        val j = try { JSONObject(raw) } catch (_: Exception) { return "error: could not locate element" }
        if (!j.optBoolean("found")) return "error: element $id is no longer on the page"
        val vvw = j.optDouble("vvw", web.width.toDouble()).takeIf { it > 0 } ?: web.width.toDouble()
        val ratio = web.width / vvw
        val px = ((j.optDouble("x") - j.optDouble("vvx")) * ratio).toFloat()
        val py = ((j.optDouble("y") - j.optDouble("vvy")) * ratio).toFloat()
        if (px < 0 || py < 0 || px > web.width || py > web.height) return "error: element $id is outside the visible area"
        delay(150)
        val before = web.url
        dispatchTap(px, py)
        settle(true)
        hideKeyboardIfNotTyping()
        return "tapped [$id]" + if (web.url != before) "; page is now ${web.url}" else ""
    }

    private fun hideKeyboardIfNotTyping() { if (!input.hasFocus()) hideKeyboard() }

    private suspend fun dispatchTap(px: Float, py: Float) {
        showTapMarker(px, py)
        val t = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, px, py, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        web.dispatchTouchEvent(down); down.recycle()
        delay(70)
        val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        web.dispatchTouchEvent(up); up.recycle()
    }

    private fun showTapMarker(px: Float, py: Float) {
        val size = dp(36)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x557CFFB2)
                setStroke(dp(2), 0xFF7CFFB2.toInt())
            }
        }
        webFrame.addView(v, FrameLayout.LayoutParams(size, size).apply { leftMargin = (px - size / 2).toInt(); topMargin = (py - size / 2).toInt() })
        v.animate().alpha(0f).scaleX(1.6f).scaleY(1.6f).setDuration(650).withEndAction { try { webFrame.removeView(v) } catch (_: Exception) {} }.start()
    }

    private fun drawFallback(): Bitmap? = try {
        if (web.width <= 0 || web.height <= 0) null else {
            val b = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(b)); b
        }
    } catch (_: Throwable) { null }

    private suspend fun captureWeb(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            val w = web.width; val h = web.height
            if (w <= 0 || h <= 0) { cont.resume(null); return@suspendCancellableCoroutine }
            val loc = IntArray(2); web.getLocationInWindow(loc)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
            android.view.PixelCopy.request(window, rect, bmp, { res ->
                if (cont.isActive) cont.resume(if (res == android.view.PixelCopy.SUCCESS) bmp else drawFallback())
            }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(drawFallback())
        }
    }

    private suspend fun saveScreenshot(): File? {
        val bmp = captureWeb() ?: return null
        return try {
            withContext(Dispatchers.IO) {
                val dir = filesDir.resolve("NanoAI/Files").apply { mkdirs() }
                val f = dir.resolve("agent_${System.currentTimeMillis()}.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                f
            }
        } catch (_: Exception) { null } finally { bmp.recycle() }
    }

    private fun manualScreenshot() {
        ui.launch {
            val f = saveScreenshot()
            if (f != null) addScreenshotRow(f, web.title ?: "") else addError("Не удалось сделать скриншот.")
        }
    }

    private suspend fun awaitAnswer(): String = suspendCancellableCoroutine { cont ->
        pendingAnswer = cont
        cont.invokeOnCancellation { pendingAnswer = null }
        updateRunUi()
        if (panelMode == 0) { panelMode = 1; applyPanelMode() }
    }

    private suspend fun awaitContinue(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        addContinueRow(text) { if (cont.isActive) cont.resume(Unit) }
        if (panelMode == 0) { panelMode = 1; applyPanelMode() }
    }

    private suspend fun confirmAction(title: String, message: String): Boolean = suspendCancellableCoroutine { cont ->
        val body = TextView(this).apply {
            text = message; textSize = 14.5f; setTextColor(Th.fg(0xFFE0E0E0.toInt()))
        }
        val dlg = styledPanelDialog(
            title, body,
            listOf(
                StyledDialogButton("Отклонить") { if (cont.isActive) cont.resume(false) },
                StyledDialogButton("Разрешить") { if (cont.isActive) cont.resume(true) }
            )
        )
        dlg.setOnDismissListener { if (cont.isActive) cont.resume(false) }
        cont.invokeOnCancellation { try { dlg.dismiss() } catch (_: Exception) {} }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        if (!running) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        job?.cancel()
        ui.cancel()
        try { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        try { web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val HOME_HTML = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>body{margin:0;background:#0b0b0e;color:#cfd0d6;font-family:sans-serif;display:flex;" +
            "align-items:center;justify-content:center;height:100vh;text-align:center}" +
            "div{padding:24px;max-width:320px}h1{font-size:22px;margin:0 0 10px;color:#fff}p{font-size:14px;line-height:1.5;color:#9a9aa5}</style>" +
            "</head><body><div><h1>Встроенный браузер</h1><p>Введите адрес сверху или поставьте задачу агенту внизу — " +
            "он сам откроет нужные страницы.</p></div></body></html>"
    }
}

object AgentModelClient {
    private val THINK = Regex("(?s)<think(ing)?>.*?</think(ing)?>")

    private fun hasImages(msgs: JSONArray): Boolean {
        for (i in 0 until msgs.length()) {
            val c = msgs.getJSONObject(i).opt("content")
            if (c is JSONArray) for (j in 0 until c.length()) if (c.optJSONObject(j)?.optString("type") == "image_url") return true
        }
        return false
    }

    private fun textOnly(msgs: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until msgs.length()) {
            val o = msgs.getJSONObject(i)
            val c = o.opt("content")
            val text = if (c is JSONArray) (0 until c.length()).mapNotNull { c.optJSONObject(it)?.takeIf { p -> p.optString("type") == "text" }?.optString("text") }.joinToString("\n") else c?.toString().orEmpty()
            out.put(JSONObject().put("role", o.optString("role")).put("content", text))
        }
        return out
    }

    private fun foldSystem(msgs: JSONArray): JSONArray {
        val sys = StringBuilder()
        val rest = ArrayList<JSONObject>()
        for (i in 0 until msgs.length()) {
            val o = msgs.getJSONObject(i)
            if (o.optString("role") == "system") sys.append(o.opt("content")?.toString().orEmpty()).append("\n\n") else rest.add(o)
        }
        val out = JSONArray()
        var folded = sys.isEmpty()
        for (o in rest) {
            if (!folded && o.optString("role") == "user") {
                val c = o.opt("content")
                if (c is JSONArray) {
                    val arr = JSONArray(); arr.put(JSONObject().put("type", "text").put("text", sys.toString().trim()))
                    for (j in 0 until c.length()) arr.put(c.get(j))
                    out.put(JSONObject().put("role", "user").put("content", arr))
                } else out.put(JSONObject().put("role", "user").put("content", sys.toString() + c.toString()))
                folded = true
            } else out.put(o)
        }
        return out
    }

    suspend fun chatRobust(ctx: Context, m: MainActivity.Model, msgs: JSONArray, maxTokens: Int, ladder: AgentLadder = AgentLadder()): String {
        var cur = msgs
        var lastErr: Exception? = null
        var emptyReason = ""
        for (attempt in 0 until 5) {
            val tokens = (maxTokens * ladder.mult).coerceAtMost(8000)
            val sendMsgs = if (ladder.noImages && hasImages(cur)) textOnly(cur) else cur
            val opts = ChatOpts(effort = ladder.effort, tolerant = true)
            try {
                val r = chat(ctx, m, sendMsgs, tokens, opts)
                val visible = THINK.replace(r, "").trim()
                if (visible.isNotEmpty() && (visible.contains('{') || attempt >= 3)) return r
                if (visible.isEmpty() && opts.reasoning.contains("\"action\"")) return opts.reasoning
                lastErr = null
                val parts = ArrayList<String>()
                if (opts.finish.isNotBlank()) parts.add("причина остановки: " + opts.finish)
                if (opts.reasoning.isNotBlank()) parts.add("весь лимит токенов ушёл на «рассуждение» модели")
                if (opts.note.isNotBlank()) parts.add(opts.note)
                emptyReason = "Модель «" + m.name + "» вернула пустой ответ" + (if (parts.isEmpty()) "" else " (" + parts.joinToString("; ") + ")") +
                    ". Попробуйте другую модель или более лёгкий режим рассуждения."
                val fin = opts.finish.lowercase()
                val cut = fin.contains("length") || fin.contains("max_tokens") || fin.contains("max-tokens") || opts.reasoning.isNotBlank()
                when {
                    cut && ladder.mult < 4 -> { ladder.mult *= 2; if (ladder.effort == null) ladder.effort = "low" }
                    cut && ladder.effort == null -> ladder.effort = "low"
                    hasImages(cur) && !ladder.noImages -> ladder.noImages = true
                    ladder.effort == null -> ladder.effort = "low"
                    else -> ladder.mult = (ladder.mult * 2).coerceAtMost(4)
                }
                kotlinx.coroutines.delay(400L * (attempt + 1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                val msg = (e.message ?: "").lowercase()
                when {
                    hasImages(cur) && !ladder.noImages && (msg.contains("image") || msg.contains("vision") || msg.contains("multimodal") ||
                        msg.contains("content") || msg.contains("400") || msg.contains("422") || msg.contains("unsupported")) -> ladder.noImages = true
                    msg.contains("system") || msg.contains("role") -> cur = foldSystem(cur)
                    msg.contains("401") || msg.contains("403") || msg.contains("ключ") || msg.contains("key") || attempt >= 2 -> throw e
                }
                kotlinx.coroutines.delay(700L * (attempt + 1))
            }
        }
        if (lastErr != null) throw lastErr
        throw AgentEmptyReply(emptyReason.ifBlank { "Модель не вернула ответ. Попробуйте другую модель или проверьте настройки сервера." })
    }

    suspend fun chat(ctx: Context, m: MainActivity.Model, msgs: JSONArray, maxTokens: Int, opts: ChatOpts? = null): String {
        if (m.serverId.isNotBlank()) {
            val server = ServerStore.find(ctx, m.serverId) ?: throw Exception("Сервер этой модели удалён.")
            if (!server.enabled) throw Exception("Сервер «${server.name}» отключён.")
            val appCtx = ctx.applicationContext
            return CustomServerClient.chat(server, m.id, msgs, maxTokens, onDelta = { }, isOnline = { CustomServerClient.isOnline(appCtx) }, opts = opts)
        }
        val apiKey = ArenaModels.apiKey(ctx, m)
        if (m.customProviderUrl.isNotBlank()) {
            return ApiClient.chatCustom(m.customProviderUrl, m.id, apiKey, msgs, maxTokens, opts)
        }
        if (apiKey.isBlank() && !ApiKeyGate.keyOptional(m.provider)) throw Exception("Для провайдера «${ApiKeyGate.providerLabel(m.provider)}» не задан API-ключ.")
        val deviceId = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
        val cfAccount = ctx.getSharedPreferences("nanoai", Context.MODE_PRIVATE).getString("cloudflare_account", "")?.trim().orEmpty()
        return ApiClient.chat(m.provider, m.id, apiKey, msgs, deviceId, cfAccount, maxTokens, opts)
    }
}

class AgentLadder {
    var mult: Int = 1
    var effort: String? = null
    var noImages: Boolean = false
}

class AgentEmptyReply(val reason: String) : Exception(reason)

object AgentChatStore {
    class Meta(val id: String, val title: String, val ts: Long)

    private fun dir(c: Context) = java.io.File(c.filesDir, "NanoAI/agent_chats").apply { mkdirs() }

    fun save(c: Context, id: String, title: String, entries: List<Pair<String, String>>) {
        try {
            val arr = JSONArray()
            entries.takeLast(400).forEach { (k, t) -> arr.put(JSONObject().put("k", k).put("t", t.take(8000))) }
            val o = JSONObject().put("id", id).put("title", title).put("ts", System.currentTimeMillis()).put("e", arr)
            java.io.File(dir(c), "$id.json").writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    fun list(c: Context): List<Meta> = (dir(c).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
        try {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            Meta(o.optString("id", f.nameWithoutExtension), o.optString("title").ifBlank { "Чат" }, o.optLong("ts"))
        } catch (_: Exception) { null }
    }.sortedByDescending { it.ts }

    fun load(c: Context, id: String): List<Pair<String, String>>? = try {
        val o = JSONObject(java.io.File(dir(c), "$id.json").readText(Charsets.UTF_8))
        val a = o.getJSONArray("e")
        (0 until a.length()).map { a.getJSONObject(it).let { e -> e.getString("k") to e.getString("t") } }
    } catch (_: Exception) { null }

    fun delete(c: Context, id: String) { java.io.File(dir(c), "$id.json").delete() }
    fun deleteAll(c: Context) { dir(c).listFiles()?.forEach { it.delete() } }
}
