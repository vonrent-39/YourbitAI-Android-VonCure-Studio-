package com.youraicqa.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class HtmlPreviewActivity : NanoBaseActivity() {
    override val glassWindow: Boolean get() = false
    private lateinit var web: WebView
    private lateinit var netBtn: TextView
    private var html: String = ""
    private var networkAllowed = true
    private var consoleErrors = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        networkAllowed = try { getSharedPreferences("nanoai", MODE_PRIVATE).getBoolean("html_preview_network", true) } catch (_: Exception) { true }
        html = try {
            File(intent.getStringExtra(EXTRA_PATH).orEmpty()).takeIf { it.isFile }?.readText() ?: ""
        } catch (_: Exception) { "" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF121216.toInt()))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        val back = TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = Loc.t("Предпросмотр HTML")
            textSize = 16f
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        netBtn = pill(Loc.t("Сеть: выкл")) {
            networkAllowed = !networkAllowed
            try { getSharedPreferences("nanoai", MODE_PRIVATE).edit().putBoolean("html_preview_network", networkAllowed).apply() } catch (_: Exception) {}
            updateNetBtn()
            try { web.settings.blockNetworkLoads = !networkAllowed } catch (_: Exception) {}
            web.settings.loadsImagesAutomatically = true
            reload()
        }
        val reloadBtn = pill(Ico.glyph(this, R.drawable.ic_regenerate, 16f, Th.fg(0xFFE0E0E0.toInt()))) { reload() }
        bar.addView(back)
        bar.addView(title)
        bar.addView(netBtn)
        bar.addView(reloadBtn)
        root.addView(bar)

        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.blockNetworkLoads = !networkAllowed
            settings.mediaPlaybackRequiresUserGesture = false
            settings.textZoom = 100
            try {
                if (android.os.Build.VERSION.SDK_INT in 29..32) settings.forceDark = WebSettings.FORCE_DARK_OFF
                if (android.os.Build.VERSION.SDK_INT >= 33) settings.isAlgorithmicDarkeningAllowed = false
            } catch (_: Throwable) {}
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportZoom(true)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    try {
                        val body = android.widget.TextView(this@HtmlPreviewActivity).apply {
                            text = message.orEmpty()
                            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
                            textSize = 14.5f
                        }
                        val d = styledPanelDialog(Loc.t("Сообщение страницы"), body, listOf(StyledDialogButton("OK") {}))
                        d.setOnDismissListener { result?.confirm() }
                    } catch (_: Exception) { result?.confirm() }
                    return true
                }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.confirm(); return true
                }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult?): Boolean {
                    try {
                        var answered = false
                        val et = android.widget.EditText(this@HtmlPreviewActivity).apply {
                            setText(defaultValue.orEmpty())
                            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                        }
                        val box = android.widget.LinearLayout(this@HtmlPreviewActivity).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            addView(android.widget.TextView(this@HtmlPreviewActivity).apply {
                                text = message.orEmpty()
                                setTextColor(Th.fg(0xFFCCCCCC.toInt()))
                                textSize = 14.5f
                            })
                            addView(et)
                        }
                        val d = styledPanelDialog(
                            Loc.t("Сообщение страницы"), box,
                            listOf(
                                StyledDialogButton(Loc.t("Отмена")) {},
                                StyledDialogButton("OK") { answered = true; result?.confirm(et.text.toString()) }
                            )
                        )
                        d.setOnDismissListener { if (!answered) result?.cancel() }
                    } catch (_: Exception) { result?.cancel() }
                    return true
                }
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    if (cm != null && cm.messageLevel() == ConsoleMessage.MessageLevel.ERROR) consoleErrors++
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    android.widget.Toast.makeText(this@HtmlPreviewActivity, Loc.t("Страница слишком тяжёлая и была остановлена"), android.widget.Toast.LENGTH_LONG).show()
                    finish()
                    return true
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val u = request.url
                    if (u.scheme == "about" || u.scheme == "data" || u.scheme == "javascript" || u.toString().startsWith("about:blank")) return false
                    if (u.host == PREVIEW_HOST) {
                        if (u.fragment != null && (u.path.isNullOrEmpty() || u.path == "/")) return false
                        return true
                    }
                    if (request.hasGesture() && (u.scheme == "http" || u.scheme == "https")) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                    }
                    return true
                }
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(web)
        setContentView(root)
        updateNetBtn()
        reload()
    }

    private fun pill(label: CharSequence, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(Th.fg(0xFFE0E0E0.toInt()))
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = androidx.core.content.ContextCompat.getDrawable(this@HtmlPreviewActivity, R.drawable.bg_copy_btn)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) }
        applyPressAnimation(PressAnimStyle.SOFT)
        setOnClickListener { onClick() }
    }

    private fun updateNetBtn() {
        netBtn.text = if (networkAllowed) Loc.t("Сеть: вкл") else Loc.t("Сеть: выкл")
    }

    private fun reload() {
        var src = html
        if (src.isBlank()) {
            android.widget.Toast.makeText(this, Loc.t("Нет кода для предпросмотра"), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!src.contains("<") && src.contains("&lt;")) {
            src = src.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
        }
        val viewport = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        val doc = if (src.contains("<html", true) || src.contains("<!doctype", true)) {
            if (src.contains("viewport", true)) src else {
                val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(src)
                if (m != null) src.substring(0, m.range.last + 1) + viewport + src.substring(m.range.last + 1)
                else Regex("<html[^>]*>", RegexOption.IGNORE_CASE).find(src)?.let { h ->
                    src.substring(0, h.range.last + 1) + "<head>$viewport</head>" + src.substring(h.range.last + 1)
                } ?: src
            }
        } else
            "<!doctype html><html><head><meta charset=\"utf-8\">$viewport</head><body>$src</body></html>"
        web.loadDataWithBaseURL("https://$PREVIEW_HOST/", doc, "text/html", "UTF-8", null)
    }

    override fun onDestroy() {
        try { web.stopLoading(); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PATH = "html_path"
        private const val MAX_CHARS = 1_500_000
        private const val PREVIEW_HOST = "preview.invalid"

        private val FRAGMENT_RE = Regex("^<(div|section|main|header|footer|article|aside|body|head|style|script|link|meta|title|table|form|button|input|label|select|textarea|span|a|img|svg|video|audio|iframe|h[1-6]|p|canvas|nav|ul|ol|li|center|details|dialog|html)[\\s>/]", RegexOption.IGNORE_CASE)

        private fun stripLead(code: String): String {
            var s = code.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            while (s.startsWith("<!--")) {
                val e = s.indexOf("-->")
                if (e < 0) break
                s = s.substring(e + 3).trimStart()
            }
            return s
        }

        fun isHtml(code: String, lang: String): Boolean {
            if (code.isBlank()) return false
            val l = lang.trim().lowercase().removePrefix("language-").removePrefix("lang-")
            if (l.startsWith("html") || l == "htm" || l == "xhtml" || l == "vue-html" || l == "markup" || l == "web" || l == "webpage" || l == "webpage") return true
            if (l.isNotEmpty() && l != "text" && l != "txt" && l != "xml" && l != "plaintext" && l != "plain") return false
            val head = stripLead(code).take(600).lowercase()
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) return true
            val early = code.take(1500).lowercase()
            if (early.contains("<!doctype html") || (early.contains("<html") && early.contains("</html"))) return true
            if (l == "xml") return false
            return FRAGMENT_RE.containsMatchIn(head)
        }

        fun compose(html: String, messageText: String): String {
            if (messageText.isBlank()) return html
            try {
                val blocks = Regex("(?s)```([A-Za-z0-9_+\\-.:]*)[ \\t]*([^\\n`]*)\\n(.*?)(?:```|\\z)").findAll(messageText).map {
                    Triple((it.groupValues[1] + " " + it.groupValues[2]).trim().lowercase(), it.groupValues[1].lowercase(), it.groupValues[3].trimEnd('\n'))
                }.toList()
                val css = blocks.filter { it.second == "css" || it.first.endsWith(".css") }.map { it.first to it.third }
                val js = blocks.filter { it.second == "js" || it.second == "javascript" || it.second == "mjs" || it.first.endsWith(".js") }.map { it.first to it.third }
                if (css.isEmpty() && js.isEmpty()) return html
                var out = html
                var cssUsed = 0
                var jsUsed = 0
                fun pick(list: List<Pair<String, String>>, file: String, used: Int): Pair<String?, Int> {
                    val byName = list.firstOrNull { it.first.contains(file.substringAfterLast('/').lowercase()) }
                    if (byName != null) return byName.second to used
                    return if (used < list.size) list[used].second to used + 1 else null to used
                }
                out = Regex("<link\\b[^>]*rel=[\"']?stylesheet[\"']?[^>]*>|<link\\b[^>]*href=[\"'][^\"']+\\.css[\"'][^>]*>", RegexOption.IGNORE_CASE).replace(out) { m ->
                    val href = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1) ?: return@replace m.value
                    if (href.contains("://") || href.startsWith("//")) return@replace m.value
                    val (body, nu) = pick(css, href, cssUsed); cssUsed = nu
                    if (body == null) m.value else "<style>\n$body\n</style>"
                }
                out = Regex("<script\\b[^>]*\\bsrc=[\"']([^\"']+)[\"'][^>]*>\\s*</script>", RegexOption.IGNORE_CASE).replace(out) { m ->
                    val src = m.groupValues[1]
                    if (src.contains("://") || src.startsWith("//")) return@replace m.value
                    val (body, nu) = pick(js, src, jsUsed); jsUsed = nu
                    if (body == null) m.value else "<script>\n${body.replace("</script", "<\\/script")}\n</script>"
                }
                return out
            } catch (_: Exception) {
                return html
            }
        }

        fun isPreviewable(fileName: String): Boolean {
            val n = fileName.lowercase()
            return n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".svg")
        }

        fun openSvg(context: Context, svg: String) {
            val page = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>html,body{margin:0;height:100%;background:#fff}" +
                "body{display:flex;align-items:center;justify-content:center}" +
                "svg{max-width:100%;max-height:100%}</style></head><body>" + svg + "</body></html>"
            open(context, page)
        }

        /** Opens an .html/.htm/.svg file in the preview screen. */
        fun openFile(context: Context, file: File) {
            try {
                val text = file.readText().take(MAX_CHARS)
                if (file.name.endsWith(".svg", true)) openSvg(context, text) else open(context, text)
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, Loc.t("Не удалось открыть предпросмотр") + ": " + (e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        }

        fun open(context: Context, html: String, messageText: String = "") {
            try {
                val f = File(context.cacheDir, "html_preview.html")
                f.writeText(compose(html, messageText).take(MAX_CHARS))
                context.startActivity(Intent(context, HtmlPreviewActivity::class.java).putExtra(EXTRA_PATH, f.absolutePath))
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, Loc.t("Не удалось открыть предпросмотр") + ": " + (e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
