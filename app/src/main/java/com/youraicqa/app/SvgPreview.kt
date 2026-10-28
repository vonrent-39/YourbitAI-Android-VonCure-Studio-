package com.youraicqa.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Inline SVG renderer: JS, event handlers and remote resources are stripped; the WebView has no JS, file or network access. */
object SvgPreview {
    private const val MAX_INPUT = 200_000

    fun looksLikeSvg(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<svg", true) || (t.startsWith("<?xml", true) && t.contains("<svg", true)) ||
            (t.startsWith("<!--") && t.contains("<svg", true))
    }

    private class Prepared(val svg: String, val ratio: Double)

    private fun prepare(rawSvg: String): Prepared? {
        var svg = rawSvg.trim().take(MAX_INPUT)
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script\\s*>"), "")
            .replace(Regex("(?is)<foreignObject\\b[^>]*>.*?</foreignObject\\s*>"), "")
            .replace(Regex("(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)"), "")
            .replace(Regex("(?i)(href|xlink:href)\\s*=\\s*(\"|')[^\"']*(https?:|data:|file:|javascript:)[^\"']*(\"|')"), "")
            .replace(Regex("(?is)<!DOCTYPE.*?>"), "")
        val svgStart = svg.indexOf("<svg", ignoreCase = true)
        if (svgStart >= 0) svg = svg.substring(svgStart)
        val svgEnd = svg.indexOf("</svg", ignoreCase = true)
        if (svgEnd >= 0) {
            val closeEnd = svg.indexOf('>', svgEnd)
            svg = if (closeEnd >= 0) svg.substring(0, closeEnd + 1) else ""
        }
        if (!svg.startsWith("<svg", true) || !svg.contains("</svg", true)) return null

        val tagEnd = svg.indexOf('>')
        if (tagEnd < 0) return null
        var openTag = svg.substring(0, tagEnd + 1)
        val body = svg.substring(tagEnd + 1)
        fun attr(name: String): String? =
            Regex("(?i)\\s$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(openTag)
                ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }
        fun num(v: String?): Double? = v?.trim()?.takeIf { !it.endsWith("%") }
            ?.let { Regex("^[0-9]*\\.?[0-9]+").find(it)?.value?.toDoubleOrNull() }
        val vb = attr("viewBox")?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }
            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
        val w = num(attr("width"))
        val h = num(attr("height"))
        val box: List<Double> = vb ?: if (w != null && h != null && w > 0 && h > 0) listOf(0.0, 0.0, w, h)
            else listOf(0.0, 0.0, 400.0, 300.0)
        openTag = openTag
            .replace(Regex("(?i)\\s(width|height|viewBox|preserveAspectRatio|style)\\s*=\\s*(\"[^\"]*\"|'[^']*')"), "")
        val extra = StringBuilder()
        if (!openTag.contains("xmlns=", true)) extra.append(" xmlns=\"http://www.w3.org/2000/svg\"")
        extra.append(" viewBox=\"${box[0]} ${box[1]} ${box[2]} ${box[3]}\"")
        extra.append(" preserveAspectRatio=\"xMidYMid meet\" width=\"100%\" height=\"100%\"")
        openTag = openTag.removeSuffix(">").removeSuffix("/") + extra + ">"
        return Prepared(openTag + body, (box[3] / box[2]).coerceIn(0.2, 2.5))
    }

    /** Returns the preview card, or null when [rawSvg] is not a renderable SVG (the caller then shows it as code). */
    fun build(ctx: Context, rawSvg: String, onCopy: (() -> Unit)?): View? {
        val p = prepare(rawSvg) ?: return null
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_code_block)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6); bottomMargin = dp(10)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        card.addView(TextView(ctx).apply {
            text = Loc.t("SVG · Предпросмотр")
            setTextColor(Th.fg(0xFF9AA0A6.toInt()))
            textSize = 12.5f
            setPadding(dp(4), dp(2), dp(4), dp(8))
        })
        val web = android.webkit.WebView(ctx).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.loadsImagesAutomatically = false
            settings.blockNetworkLoads = true
            settings.textZoom = 100
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            setInitialScale(100)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isLongClickable = false
            // Let touches reach the parent (message scrolling) instead of the WebView
            setOnTouchListener { _, ev -> ev.actionMasked == android.view.MotionEvent.ACTION_MOVE }
            webViewClient = android.webkit.WebViewClient()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val width = r - l
                if (width > 0) {
                    val target = (width * p.ratio).toInt().coerceIn(dp(100), dp(480))
                    if (v.layoutParams.height != target) {
                        v.layoutParams.height = target
                        v.post { v.requestLayout() }
                    }
                }
            }
        }
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no\">" +
            "<style>html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:transparent}" +
            "svg{display:block;width:100%;height:100%}</style></head><body>${p.svg}</body></html>"
        web.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        card.addView(web)
        if (onCopy != null) {
            card.addView(TextView(ctx).apply {
                text = Loc.t("Копировать SVG-код")
                setTextColor(Th.fg(0xFF8AB4F8.toInt()))
                textSize = 12.5f
                setPadding(dp(4), dp(8), dp(4), dp(2))
                setOnClickListener { onCopy() }
                applyPressAnimation(PressAnimStyle.SOFT)
            })
        }
        return card
    }
}
