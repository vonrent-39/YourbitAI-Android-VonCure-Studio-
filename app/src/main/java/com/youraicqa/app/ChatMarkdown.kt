package com.youraicqa.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class ChatMarkdown(
    private val act: NanoBaseActivity,
    private val isBusy: () -> Boolean,
    private val sendText: (String) -> Unit
) {
    private sealed class Seg {
        data class Text(val value: String) : Seg()
        data class Table(val rows: List<List<String>>) : Seg()
    }

    private val prefs = act.getSharedPreferences("nanoai", Context.MODE_PRIVATE)
    private val answeredPolls: MutableSet<String> by lazy {
        prefs.getStringSet(KEY_ANSWERED_POLLS, emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun markPollAnswered(pollKey: String) {
        answeredPolls.add(pollKey)
        prefs.edit().putStringSet(KEY_ANSWERED_POLLS, answeredPolls).apply()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), act.resources.displayMetrics).toInt()

    private fun copyToClipboard(text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Toast.makeText(act, Loc.t("Скопировано"), Toast.LENGTH_SHORT).show()
    }

    fun renderText(body: LinearLayout, raw: String) {
        var src = MathFormat.joinDisplayBlocks(raw.replace("\r\n", "\n").replace("\r", "\n"))
        src = normalizePollBlocks(src)
        var last = 0
        for (m in POLL_CANON_REGEX.findAll(src)) {
            val before = src.substring(last, m.range.first)
            if (before.isNotBlank()) renderTextAndTables(body, before)
            val pollBody = m.groupValues[1].trim('\n')
            if (pollBody.isNotBlank()) body.addView(makePollCard(pollBody))
            last = m.range.last + 1
        }
        val rest = src.substring(last.coerceAtMost(src.length))
        if (rest.isNotBlank()) renderTextAndTables(body, rest)
    }

    private fun renderTextAndTables(body: LinearLayout, text: String) {
        for (seg in splitTextAndTables(text)) {
            when (seg) {
                is Seg.Text -> appendRichText(body, seg.value)
                is Seg.Table -> body.addView(makeTableBlock(seg.rows))
            }
        }
    }

    fun makeSuggestions(items: List<String>): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        items.forEach { suggestion ->
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(act, R.drawable.bg_suggestion_pill)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
                setPadding(dp(12), dp(9), dp(12), dp(9))
                isClickable = true
                isFocusable = true
                applyPressAnimation(PressAnimStyle.SOFT)
            }
            row.addView(TextView(act).apply {
                text = "↪"
                setTextColor(ContextCompat.getColor(act, R.color.app_accent))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
            })
            row.addView(TextView(act).apply {
                text = suggestion
                setTextColor(ContextCompat.getColor(act, R.color.app_text_secondary))
                textSize = 13.5f
            })
            row.setOnClickListener { if (!isBusy()) sendText(suggestion) }
            box.addView(row)
        }
        return box
    }

    fun prepare(text: String): String = normalizePollBlocks(text.replace("\r\n", "\n").replace("\r", "\n"))

    fun splitSuggestions(text: String): Pair<String, List<String>> = extractSuggestions(text)

    companion object {
        private const val KEY_ANSWERED_POLLS = "answered_polls_arena_v1"
    }

    private val POLL_CANON_REGEX = Regex("(?m)^```poll\\n([\\s\\S]*?)\\n```[ \\t]*$")

    private val POLL_OPEN_LINE_REGEX = Regex(
        "^[ \\t]*>?[ \\t]*(?:`{3,}|~{3,})[ \\t]*(?:polls?|pools?|опрос|голосование)\\b[ \\t:]*(.*)$",
        RegexOption.IGNORE_CASE
    )

    private val FENCE_ONLY_LINE_REGEX = Regex("^[ \\t]*>?[ \\t]*(?:`{3,}|~{3,})[ \\t]*$")

    private val POLL_OPTION_LINE_REGEX = Regex("^(?:[-*•–—]\\s+|\\d+[.)]\\s+).+")

    private val SUGGESTIONS_TAG_REGEX = Regex(
        "(?isu)```[ \\t]*(?:suggestions?|suggest|follow-?ups?|подсказки|варианты)[ \\t]*\\r?\\n(.*?)(?:```|\\z)"
    )

    private val SUGGESTIONS_TAIL_FENCE_REGEX = Regex(
        "(?s)```[ \\t]*(?:text|txt|markdown|md|plain)?[ \\t]*\\r?\\n([^`]*?)```\\s*\\z"
    )

    private fun isTableRowLine(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2) return true
        if (trimmed.startsWith("|") && trimmed.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty()) return true
        return false
    }

    private fun isEmptyCell(s: String): Boolean {
        var t = s.trim()
        t = t.replace("<br>", "", ignoreCase = true)
        t = t.replace("<br/>", "", ignoreCase = true)
        t = t.replace("<br />", "", ignoreCase = true)
        t = t.replace("&nbsp;", "", ignoreCase = true)
        t = t.replace("`", "")
        t = t.replace("*", "")
        t = t.replace("_", "")
        t = t.trim()
        if (t.isEmpty()) return true
        if (t.all { ch -> ch == '-' || ch == ':' || ch == ' ' || ch == '─' || ch == '|' || ch == '.' }) return true
        return false
    }

    private fun cleanCell(s: String): String {
        var t = s.trim()
        t = t.replace("<br>", "\n", ignoreCase = true)
        t = t.replace("<br/>", "\n", ignoreCase = true)
        t = t.replace("<br />", "\n", ignoreCase = true)
        t = t.replace("&nbsp;", " ", ignoreCase = true)
        t = t.replace(Regex("<[^>]+>"), "")
        return t.trim()
    }

    private fun normalizeTable(tableLines: List<String>): List<List<String>> {
        val parsed = mutableListOf<List<String>>()
        for (line in tableLines) {
            var r = line.trim()
            val onlySep = r.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").replace("─", "")
            if (onlySep.isEmpty()) continue

            if (r.startsWith("|")) r = r.removePrefix("|")
            if (r.endsWith("|")) r = r.removeSuffix("|")
            val cells = r.split("|").map { cleanCell(it) }
            if (cells.any { !isEmptyCell(it) }) parsed.add(cells)
        }
        if (parsed.isEmpty()) return emptyList()

        val maxCols = parsed.maxOf { it.size }
        val used = BooleanArray(maxCols)
        for (row in parsed) {
            for (c in row.indices) {
                if (!isEmptyCell(row[c])) used[c] = true
            }
        }
        val usedIdx = used.withIndex().filter { it.value }.map { it.index }
        if (usedIdx.isEmpty()) return emptyList()

        val result = parsed.map { row ->
            usedIdx.map { c -> if (c < row.size) row[c] else "" }
        }.filter { row -> row.any { !isEmptyCell(it) } }

        if (result.size == 1 && result[0].size == 1) return emptyList()
        return result
    }

    private fun splitTextAndTables(text: String): List<Seg> {
        val lines = text.split("\n")
        val out = mutableListOf<Seg>()
        var i = 0
        val textBuf = StringBuilder()
        fun flushText() {
            if (textBuf.isNotEmpty()) {
                val t = textBuf.toString().trimEnd()
                if (t.isNotBlank()) out.add(Seg.Text(t))
                textBuf.clear()
            }
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (isTableRowLine(trimmed)) {
                flushText()
                val tableLines = mutableListOf<String>()
                while (i < lines.size && isTableRowLine(lines[i].trim())) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                val cleaned = normalizeTable(tableLines)
                if (cleaned.isNotEmpty() && cleaned[0].size >= 1 && cleaned.size >= 1) {
                    if (!(cleaned[0].size == 1 && cleaned.size == 1)) {
                        out.add(Seg.Table(cleaned))
                    } else {
                        for (tl in tableLines) {
                            if (textBuf.isNotEmpty()) textBuf.append("\n")
                            textBuf.append(tl)
                        }
                        flushText()
                    }
                }
                continue
            }
            if (textBuf.isNotEmpty()) textBuf.append("\n")
            textBuf.append(line)
            i++
        }
        flushText()
        return out
    }

    private fun makeHeading(title: String, level: Int): TextView {
        val sizes = floatArrayOf(26f, 22f, 18f, 16f, 15f, 14f)
        val size = sizes.getOrElse(level - 1) { 15f }
        return TextView(act).apply {
            text = applyInlineMarkdown(title)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = size
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(0, dp(if (level <= 2) 14 else 10), 0, dp(6))
            setTextIsSelectable(true)
        }
    }

    private fun makeHorizontalRule(): View {
        return View(act).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            )
            lp.topMargin = dp(10)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setBackgroundColor(Th.bg(0xFF44474F.toInt()))
        }
    }

    private fun makeQuoteBlock(content: String): View {
        val outer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            val bg = GradientDrawable().apply {
                setColor(Th.bg(0xFF1A1C20.toInt()))
                cornerRadius = dp(12).toFloat()
            }
            background = bg
            setPadding(0, dp(10), dp(12), dp(10))
        }
        val bar = View(act).apply {
            layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                marginStart = dp(10)
                marginEnd = dp(12)
            }
            val bg = GradientDrawable().apply {
                setColor(Th.bg(0xFF8AB4F8.toInt()))
                cornerRadius = dp(3).toFloat()
            }
            background = bg
        }
        val tv = TextView(act).apply {
            text = applyInlineMarkdown(content)
            setTextColor(Th.fg(0xFFCFD3DA.toInt()))
            textSize = 15.5f
            setTypeface(typeface, Typeface.ITALIC)
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        outer.addView(bar)
        outer.addView(tv)
        return outer
    }

    private fun makeListBlock(items: List<Pair<Int, String>>, ordered: Boolean): LinearLayout {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(6))
        }
        var num = 1
        for ((level, content) in items) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(level * 16), dp(2), 0, dp(2))
            }
            val bullet = TextView(act).apply {
                text = if (ordered) {
                    if (level == 0) "${num}." else "${('a' + (num - 1).coerceAtMost(25))}."
                } else {
                    if (level == 0) "•" else "◦"
                }
                setTextColor(Th.fg(0xFFB0B4BA.toInt()))
                textSize = 15.5f
                layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tv = TextView(act).apply {
                text = applyInlineMarkdown(content)
                setTextColor(Th.fg(0xFFE8E8E8.toInt()))
                textSize = 15.5f
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(bullet)
            row.addView(tv)
            box.addView(row)
            num++
        }
        return box
    }

    private fun makeTableBlock(rows: List<List<String>>): View {
        if (rows.isEmpty()) return View(act)
        val colCount = rows.maxOf { it.size }.coerceAtLeast(1)

        val card = TableCardLayout(
            act, dp(16).toFloat(), dp(1).toFloat(), Th.line(0xFF33363D.toInt())
        ).apply {
            background = ContextCompat.getDrawable(act, R.drawable.bg_table_card)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        val headerBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(act, R.drawable.bg_table_header)
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        val titleTv = TextView(act).apply {
            text = "Таблица"
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copyTableBtn = ImageView(act).apply {
            setImageResource(R.drawable.ic_copy)
            setColorFilter(Th.fg(0xFFCCCCCC.toInt()))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = ContextCompat.getDrawable(act, R.drawable.bg_copy_btn)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                val tsv = rows.joinToString("\n") { it.joinToString("\t") }
                copyToClipboard(tsv)
            }
            contentDescription = "Скопировать таблицу"
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        }
        headerBar.addView(titleTv)
        headerBar.addView(copyTableBtn)
        card.addView(headerBar)

        val hScroll = HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val table = TableLayout(act).apply {
            isStretchAllColumns = true
            setPadding(0, 0, 0, 0)
        }

        val minCol = dp(96)

        for ((rIdx, row) in rows.withIndex()) {
            val tr = TableRow(act)
            val isHeader = rIdx == 0
            for (c in 0 until colCount) {
                val raw = if (c < row.size) row[c] else ""
                val cell = TextView(act).apply {
                    text = applyInlineMarkdown(raw)
                    setTextColor(if (isHeader) Th.fg(0xFFFFFFFF.toInt()) else Th.fg(0xFFE8E8E8.toInt()))
                    textSize = if (isHeader) 13.5f else 13f
                    if (isHeader) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setLineSpacing(0f, 1.15f)
                    breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                    setTextIsSelectable(true)
                    minWidth = minCol
                    val bg = GradientDrawable().apply {
                        setColor(if (isHeader) Th.bg(0xFF2A2D35.toInt()) else Th.bg(0xFF1E1F24.toInt()))
                        setStroke(1, Th.line(0xFF33363D.toInt()))
                    }
                    background = bg
                }
                val lp = TableRow.LayoutParams(
                    TableRow.LayoutParams.WRAP_CONTENT,
                    TableRow.LayoutParams.MATCH_PARENT
                )
                lp.weight = 1f
                tr.addView(cell, lp)
            }
            table.addView(tr)
        }
        hScroll.addView(table, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(hScroll)
        return card
    }

    private fun makeBodyText(raw: String): View {
        if (raw.contains("[[BULB]]")) {
            val clean = raw.replace("[[BULB]]", "").trimStart()
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(6), 0, dp(4))
            }
            row.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_bulb)
                setColorFilter(Th.fg(0xFFFFD54F.toInt()))
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginEnd = dp(5)
                    topMargin = dp(2)
                }
                contentDescription = "Совет"
            })
            row.addView(makeTextBlock(clean).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            return row
        }
        return makeTextBlock(raw)
    }

    private fun makeTextBlock(raw: String): TextView {
        return TextView(act).apply {
            text = applyInlineMarkdown(raw)
            setTextColor(Th.fg(0xFFE8E8E8.toInt()))
            textSize = 16f
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Th.bg(0x33FFFFFF)
            setPadding(0, dp(2), 0, dp(6))
            setLineSpacing(0f, 1.2f)
        }
    }

    private fun formatInlineMath(raw: String): String = MathFormat.convert(raw)

    private fun applyInlineMarkdown(srcRaw: String): SpannableString {
        val src = formatInlineMath(srcRaw)
        val mdLinkPattern = Regex("""\[([^\[\]]{1,300})\]\((https?://[^\s()]+)\)""")
        val mdLinks = mutableListOf<Pair<String, String>>()
        val afterMdLinks = mdLinkPattern.replace(src) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2].trimEnd('.', ',', ';', '!', '?')
            mdLinks.add(label to url)
            "${mdLinks.size - 1}"
        }
        val urlPattern = Regex("""https?://[^\s<>\[\]()"']+""")
        val urls = mutableListOf<String>()
        val protected = urlPattern.replace(afterMdLinks) { m ->
            urls.add(m.value.trimEnd('.', ',', ';', '!', '?', ')', ']'))
            "${urls.size - 1}"
        }
        val out = StringBuilder()
        data class S(val start: Int, val end: Int, val type: Int) // 0 bold 1 italic 2 code 3 underline 4 link 5 strikethrough
        val spans = mutableListOf<S>()
        val linkUrls = mutableMapOf<Int, String>()
        var i = 0
        var bold = -1
        var italic = -1
        var code = -1
        var under = -1
        var strike = -1
        var strikeKind = 0 // 1 ~~, 2 <s>, 3 <del>, 4 <strike>
        while (i < protected.length) {
            when {
                protected.startsWith("**", i) || protected.startsWith("__", i) -> {
                    if (bold == -1) bold = out.length else {
                        spans.add(S(bold, out.length, 0)); bold = -1
                    }
                    i += 2
                }
                code == -1 && strike != -1 && StrikeMd.closeLen(protected, i, strikeKind) > 0 -> {
                    val cl = StrikeMd.closeLen(protected, i, strikeKind)
                    spans.add(S(strike, out.length, 5)); strike = -1; strikeKind = 0
                    i += cl
                }
                code == -1 && strike == -1 && StrikeMd.openKind(protected, i) != 0 -> {
                    strikeKind = StrikeMd.openKind(protected, i)
                    strike = out.length
                    i += StrikeMd.openLen(strikeKind)
                }
                protected.startsWith("++", i) -> {
                    if (under == -1) under = out.length else {
                        spans.add(S(under, out.length, 3)); under = -1
                    }
                    i += 2
                }
                protected.startsWith("<u>", i) || protected.startsWith("<U>", i) -> {
                    under = out.length
                    i += 3
                }
                protected.startsWith("</u>", i) || protected.startsWith("</U>", i) -> {
                    if (under != -1) {
                        spans.add(S(under, out.length, 3)); under = -1
                    }
                    i += 4
                }
                protected[i] == '`' -> {
                    if (code == -1) code = out.length else {
                        spans.add(S(code, out.length, 2)); code = -1
                    }
                    i++
                }
                protected[i] == '*' || protected[i] == '_' -> {
                    if (italic == -1) italic = out.length else {
                        spans.add(S(italic, out.length, 1)); italic = -1
                    }
                    i++
                }
                else -> {
                    if (protected[i] == '') {
                        val j = protected.indexOf('', i)
                        if (j > i) {
                            val idx = protected.substring(i + 1, j).toIntOrNull()
                            if (idx != null && idx in mdLinks.indices) {
                                val (label, u) = mdLinks[idx]
                                val st = out.length
                                out.append(label)
                                spans.add(S(st, out.length, 4))
                                linkUrls[st] = u
                                i = j + 1
                                continue
                            }
                        }
                    }
                    if (protected[i] == '') {
                        val j = protected.indexOf('', i)
                        if (j > i) {
                            val idx = protected.substring(i + 1, j).toIntOrNull()
                            if (idx != null && idx in urls.indices) {
                                val u = urls[idx]
                                val st = out.length
                                out.append(u)
                                spans.add(S(st, out.length, 4))
                                linkUrls[st] = u
                                i = j + 1
                                continue
                            }
                        }
                    }
                    out.append(protected[i]); i++
                }
            }
        }
        val ss = SpannableString(MathFormat.restore(out.toString()))
        for (s in spans) {
            if (s.start >= s.end || s.end > ss.length) continue
            when (s.type) {
                0 -> {
                    ss.setSpan(
                        StyleSpan(Typeface.BOLD),
                        s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    ss.setSpan(
                        android.text.style.TypefaceSpan("sans-serif-black"),
                        s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                1 -> ss.setSpan(StyleSpan(Typeface.ITALIC), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                2 -> {
                    ss.setSpan(TypefaceSpan("monospace"), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ss.setSpan(
                        android.text.style.BackgroundColorSpan(Th.bg(0xFF2A2D33.toInt())),
                        s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                3 -> ss.setSpan(
                    android.text.style.UnderlineSpan(),
                    s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                5 -> ss.setSpan(
                    android.text.style.StrikethroughSpan(),
                    s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                4 -> {
                    val url = linkUrls[s.start] ?: ss.substring(s.start, s.end)
                    ss.setSpan(ForegroundColorSpan(Th.fg(0xFF64B5F6.toInt())), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ss.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.color = Th.fg(0xFF64B5F6.toInt())
                            ds.isUnderlineText = true
                        }
                    }, s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
                return MathSpans.apply(ss)
    }

    private fun makePollCard(raw: String): View {
        val lines = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val question = lines.firstOrNull { !it.matches(Regex("^[-*•]\\s+.*")) && !it.matches(Regex("^\\d+[.)]\\s+.*")) }
            ?.removePrefix("Вопрос:")?.removePrefix("Question:")?.trim()
        val options = lines
            .filter { it.matches(Regex("^[-*•]\\s+.*")) || it.matches(Regex("^\\d+[.)]\\s+.*")) }
            .map { it.replace(Regex("^[-*•]\\s+"), "").replace(Regex("^\\d+[.)]\\s+"), "").trim() }
            .filter { it.isNotBlank() }
            .take(6)
        if (question.isNullOrBlank() || options.size < 2) {
            return makeBodyText(raw)
        }
        val pollKey = "poll_${(question + options.joinToString()).hashCode()}"
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF1E1E24.toInt()))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(8)
            lp.bottomMargin = dp(6)
            layoutParams = lp
        }
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        head.addView(ImageView(act).apply {
            setImageDrawable(ContextCompat.getDrawable(act, R.drawable.ic_bulb))
            setColorFilter(ContextCompat.getColor(act, R.color.app_accent))
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
        })
        head.addView(TextView(act).apply {
            text = question
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(head)
        val optionRows = mutableListOf<Pair<LinearLayout, TextView>>()
        val alreadyAnswered = pollKey in answeredPolls
        lateinit var customRow: LinearLayout
        lateinit var customInput: EditText
        lateinit var customSend: ImageView
        fun lockPoll(chosenText: String, chosenTv: TextView?) {
            markPollAnswered(pollKey)
            optionRows.forEach { (r, tv) ->
                r.isClickable = false
                r.isFocusable = false
                tv.setTextColor(ContextCompat.getColor(act, R.color.app_text_secondary))
                tv.setTypeface(tv.typeface, Typeface.NORMAL)
            }
            chosenTv?.setTextColor(ContextCompat.getColor(act, R.color.app_accent))
            chosenTv?.setTypeface(chosenTv.typeface, Typeface.BOLD)
            customRow.visibility = View.GONE
            sendText(chosenText)
        }
        options.forEach { opt ->
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(act, R.drawable.bg_suggestion_pill)
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = !alreadyAnswered
                isFocusable = !alreadyAnswered
                if (!alreadyAnswered) applyPressAnimation(PressAnimStyle.SOFT)
            }
            val optTv = TextView(act).apply {
                text = opt
                setTextColor(ContextCompat.getColor(act, R.color.app_text_secondary))
                textSize = 13.5f
            }
            row.addView(optTv)
            row.setOnClickListener {
                if (isBusy() || pollKey in answeredPolls) return@setOnClickListener
                lockPoll(opt, optTv)
            }
            optionRows.add(row to optTv)
            card.addView(row)
        }

        customRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(act, R.drawable.bg_input_pill)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
            setPadding(dp(12), dp(2), dp(6), dp(2))
            visibility = if (alreadyAnswered) View.GONE else View.VISIBLE
        }
        customInput = EditText(act).apply {
            hint = "Свой вариант…"
            setTextColor(ContextCompat.getColor(act, R.color.app_text_primary))
            setHintTextColor(ContextCompat.getColor(act, R.color.app_hint))
            textSize = 13.5f
            background = null
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        customSend = ImageView(act).apply {
            setImageDrawable(ContextCompat.getDrawable(act, R.drawable.ic_send_arrow))
            setColorFilter(ContextCompat.getColor(act, R.color.app_accent))
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "Отправить свой вариант"
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                val own = customInput.text?.toString()?.trim().orEmpty()
                if (own.isEmpty() || isBusy() || pollKey in answeredPolls) return@setOnClickListener
                lockPoll(own, null)
            }
        }
        customRow.addView(customInput)
        customRow.addView(customSend)
        card.addView(customRow)
        return card
    }

    private fun normalizePollBlocks(text: String): String {
        if (!text.contains("```") && !text.contains("~~~")) return text
        val lines = text.split("\n")
        val out = ArrayList<String>(lines.size)
        var i = 0
        var changed = false
        while (i < lines.size) {
            val open = POLL_OPEN_LINE_REGEX.find(lines[i])
            if (open == null) { out.add(lines[i]); i++; continue }
            changed = true
            val body = ArrayList<String>()
            var optionCount = 0
            var closedInline = false
            fun take(rawLine: String) {
                var t = rawLine.trim().removePrefix(">").trim()
                if (t.endsWith("```")) { t = t.removeSuffix("```").trim(); closedInline = true }
                if (t.isEmpty()) return
                if (POLL_OPTION_LINE_REGEX.matches(t)) optionCount++
                body.add(t)
            }
            take(open.groupValues[1])
            i++
            while (i < lines.size && !closedInline) {
                val l = lines[i]
                if (FENCE_ONLY_LINE_REGEX.matches(l)) { i++; break }
                val t = l.trim()
                if (t.isEmpty()) {
                    i++
                    if (optionCount >= 2) break
                    continue
                }
                if (optionCount >= 2 && !POLL_OPTION_LINE_REGEX.matches(t.removePrefix(">").trim())) break
                take(l)
                i++
            }
            val question = body.firstOrNull { !POLL_OPTION_LINE_REGEX.matches(it) }
            if (question != null && optionCount >= 2) {
                if (out.isNotEmpty() && out.last().isNotBlank()) out.add("")
                out.add("```poll")
                out.addAll(body)
                out.add("```")
                out.add("")
            } else {
                body.forEach { out.add(it) }
            }
        }
        return if (changed) out.joinToString("\n") else text
    }

    private fun extractSuggestions(text: String): Pair<String, List<String>> {
        var cleaned = text
        val raw = mutableListOf<String>()
        val explicit = SUGGESTIONS_TAG_REGEX.findAll(text).toList()
        if (explicit.isNotEmpty()) {
            explicit.forEach { raw += it.groupValues[1].lines() }
            cleaned = SUGGESTIONS_TAG_REGEX.replace(text, "").trimEnd()
        } else {
            val tail = SUGGESTIONS_TAIL_FENCE_REGEX.find(text)
            if (tail != null) {
                val lines = tail.groupValues[1].lines().map { it.trim() }.filter { it.isNotBlank() }
                val looksLikeSuggestions = lines.size in 2..4 && lines.all { l ->
                    l.length <= 80 && !looksLikeCodeLine(l)
                }
                if (looksLikeSuggestions) {
                    raw += lines
                    cleaned = text.substring(0, tail.range.first).trimEnd()
                }
            }
        }
        if (raw.isEmpty()) return text to emptyList()
        val seen = HashSet<String>()
        val items = raw
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                var s = line.trimStart('-', '•', '*', '–', '—', ' ', '>')
                s = s.replace(Regex("^\\d+[.)]\\s*"), "")
                s = s.trim().trim('"', '“', '”', '«', '»', '`')
                if (s.startsWith("'") && s.endsWith("'") && s.length >= 2) s = s.substring(1, s.length - 1)
                s.trim()
            }
            .filter { it.length in 3..80 && !looksLikeCodeLine(it) }
            .filter { seen.add(it.lowercase().replace(Regex("[\\p{Punct}\\s…—–]+"), "")) }
            .take(3)
        return cleaned to items
    }

    private fun appendRichText(body: LinearLayout, raw: String) {
        val lines = raw.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()

            if (t.matches(Regex("-{3,}|_{3,}|\\*{3,}"))) {
                body.addView(makeHorizontalRule())
                i++
                continue
            }

            val hMatch = Regex("^(#{1,6})\\s+(.*)$").find(t)
            if (hMatch != null) {
                val level = hMatch.groupValues[1].length
                val title = hMatch.groupValues[2]
                body.addView(makeHeading(title, level))
                i++
                continue
            }

            if (t.startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trimStart())
                    i++
                }
                body.addView(makeQuoteBlock(quoteLines.joinToString("\n")))
                continue
            }

            if (t.matches(Regex("^[-*+]\\s+.*"))) {
                val items = mutableListOf<Pair<Int, String>>()
                while (i < lines.size) {
                    val lt = lines[i]
                    val indent = lt.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    val tr = lt.trim()
                    val m = Regex("^([-*+])\\s+(.*)$").find(tr)
                    if (m != null) {
                        val level = if (indent >= 2) 1 else 0
                        items.add(level to m.groupValues[2])
                        i++
                    } else if (tr.isEmpty()) {
                        i++
                        break
                    } else break
                }
                body.addView(makeListBlock(items, ordered = false))
                continue
            }

            if (t.matches(Regex("^\\d+[.)]\\s+.*"))) {
                val items = mutableListOf<Pair<Int, String>>()
                var n = 1
                while (i < lines.size) {
                    val lt = lines[i]
                    val indent = lt.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                    val tr = lt.trim()
                    val m = Regex("^(\\d+)[.)]\\s+(.*)$").find(tr)
                    if (m != null) {
                        val level = if (indent >= 2) 1 else 0
                        items.add(level to m.groupValues[2])
                        n++
                        i++
                    } else if (tr.isEmpty()) {
                        i++
                        break
                    } else break
                }
                body.addView(makeListBlock(items, ordered = true))
                continue
            }

            if (t.isEmpty()) {
                i++
                continue
            }
            val para = StringBuilder(line)
            i++
            while (i < lines.size) {
                val n = lines[i]
                val nt = n.trim()
                if (nt.isEmpty()) break
                if (nt.startsWith(">") || nt.matches(Regex("^#{1,6}\\s+.*")) ||
                    nt.matches(Regex("^[-*+]\\s+.*")) || nt.matches(Regex("^\\d+[.)]\\s+.*")) ||
                    nt.matches(Regex("-{3,}|_{3,}|\\*{3,}"))) break
                para.append("\n").append(n)
                i++
            }
            body.addView(makeBodyText(para.toString()))
        }
    }

    private fun looksLikeCodeLine(l: String): Boolean =
        l.startsWith("```") || l.startsWith("#") || l.startsWith("//") || l.startsWith("<") ||
            l.contains("{") || l.contains("}") || l.contains("`") || l.contains("=>") ||
            l.contains(");") || l.contains("();") || l.endsWith(";")
}
