package com.youraicqa.app

object MathFormat {
    private const val S_UNDERSCORE = '\uE010'
    private const val S_STAR = '\uE011'
    private const val S_PLUS = '\uE012'

    fun restore(s: String): String {
        var has = false
        for (ch in s) if (ch in '\uE010'..'\uE012') { has = true; break }
        if (!has) return s
        return s.replace(S_UNDERSCORE, '_').replace(S_STAR, '*').replace(S_PLUS, '+')
    }

    fun joinDisplayBlocks(raw: String): String {
        if (raw.indexOf('$') < 0 && !raw.contains("\\[")) return raw
        val parts = raw.split("```")
        if (parts.size == 1) return joinIn(raw)
        val sb = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) sb.append("```")
            sb.append(if (i % 2 == 0) joinIn(p) else p)
        }
        return sb.toString()
    }

    private val DOLLAR_BLOCK = Regex("""\$\$([\s\S]{1,2000}?)\$\$""")
    private val BRACKET_BLOCK = Regex("""\\\[([\s\S]{1,2000}?)\\\]""")
    private val NEWLINES = Regex("""\s*\n\s*""")

    private fun joinIn(s: String): String {
        var t = DOLLAR_BLOCK.replace(s) { m -> "\$\$" + m.groupValues[1].trim().replace(NEWLINES, " ") + "\$\$" }
        t = BRACKET_BLOCK.replace(t) { m -> "\\[" + m.groupValues[1].trim().replace(NEWLINES, " ") + "\\]" }
        return t
    }

    fun convert(rawIn: String): String {
        if (rawIn.isEmpty()) return rawIn
        val raw = normalizeCombiningStrike(rawIn)
        if (raw.indexOf('\\') < 0 && raw.indexOf('$') < 0) return raw
        val parts = raw.split('`')
        if (parts.size == 1) return convertPlain(raw)
        val sb = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) sb.append('`')
            sb.append(if (i % 2 == 0) convertPlain(p) else p)
        }
        return sb.toString()
    }

    private val STRIKE_COMB_RUN = Regex("(?:[^\\u0335-\\u0338][\\u0335-\\u0337])+")
    private val STRIKE_SLASH_RUN = Regex("(?:[^\\u0335-\\u0338]\\u0338){2,}")

    private fun normalizeCombiningStrike(s: String): String {
        var has = false
        for (ch in s) if (ch in '\u0335'..'\u0338') { has = true; break }
        if (!has) return s
        val parts = s.split('`')
        val sb = StringBuilder()
        for ((i, p) in parts.withIndex()) {
            if (i > 0) sb.append('`')
            if (i % 2 == 1) { sb.append(p); continue }
            var t = STRIKE_COMB_RUN.replace(p) { m -> strikeWrap(m.value) }
            t = STRIKE_SLASH_RUN.replace(t) { m -> strikeWrap(m.value) }
            sb.append(t)
        }
        return sb.toString()
    }

    private fun strikeWrap(run: String): String {
        val base = run.filter { it !in '\u0335'..'\u0338' }
        return if (base.isEmpty()) "" else "" + Mk.ST_OPEN + base + Mk.ST_CLOSE
    }

    private fun convertPlain(text: String): String {
        val n = text.length
        val sb = StringBuilder()
        var i = 0
        var last = 0
        while (i < n) {
            val c = text[i]
            var end = -1
            var openLen = 0
            var closeLen = 0
            if (c == '\\' && i + 1 < n && (text[i + 1] == '(' || text[i + 1] == '[')) {
                val close = if (text[i + 1] == '(') "\\)" else "\\]"
                val j = text.indexOf(close, i + 2)
                if (j > 0) { openLen = 2; closeLen = 2; end = j }
            } else if (c == '\\' && i + 1 < n && text[i + 1] == '$') {
                i += 2
                continue
            } else if (c == '$') {
                if (i + 1 < n && text[i + 1] == '$') {
                    val j = text.indexOf("\$\$", i + 2)
                    if (j > i + 2) { openLen = 2; closeLen = 2; end = j }
                    else { i += 2; continue }
                } else if (i + 1 < n && !text[i + 1].isWhitespace()) {
                    val j = findClosingDollar(text, i + 1)
                    if (j > 0) { openLen = 1; closeLen = 1; end = j }
                }
            }
            if (end > 0) {
                sb.append(convertBare(text.substring(last, i)))
                val inner = text.substring(i + openLen, end)
                sb.append(protect(latexToText(inner, false)))
                i = end + closeLen
                last = i
            } else {
                i++
            }
        }
        sb.append(convertBare(text.substring(last)))
        return sb.toString()
    }

    private fun findClosingDollar(text: String, from: Int): Int {
        val n = text.length
        var j = from
        val limit = minOf(n, from + 300)
        while (j < limit) {
            val ch = text[j]
            if (ch == '\n') return -1
            if (ch == '\\') { j += 2; continue }
            if (ch == '$') {
                if (j == from) return -1
                if (text[j - 1].isWhitespace()) return -1
                if (j + 1 < n && text[j + 1].isDigit()) return -1
                val inner = text.substring(from, j)
                val looksLikeMath = inner.any { it in "\\^_{}=<>+-*/" } ||
                    (inner.length == 1 && inner[0].isLetter())
                return if (looksLikeMath) j else -1
            }
            j++
        }
        return -1
    }

    private fun convertBare(seg: String): String {
        if (seg.isEmpty() || seg.indexOf('\\') < 0) return seg
        return latexToText(seg, true)
    }

    private fun protect(s: String): String {
        if (s.indexOf('_') < 0 && s.indexOf('*') < 0 && s.indexOf('+') < 0) return s
        return s.replace('_', S_UNDERSCORE).replace('*', S_STAR).replace('+', S_PLUS)
    }

    private class R(val text: String, val next: Int)

    private class Group(val inner: String, val next: Int)

    private fun readGroup(src: String, from: Int): Group? {
        var k = from
        val n = src.length
        while (k < n && src[k] == ' ') k++
        if (k >= n) return null
        if (src[k] == '{') {
            var depth = 0
            var j = k
            while (j < n) {
                val ch = src[j]
                if (ch == '\\') { j += 2; continue }
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return Group(src.substring(k + 1, j), j + 1)
                }
                j++
            }
            return null
        }
        if (src[k] == '\\') {
            var j = k + 1
            if (j < n && src[j].isLetter()) {
                while (j < n && src[j].isLetter()) j++
            } else if (j < n) {
                j++
            }
            return Group(src.substring(k, j), j)
        }
        return Group(src[k].toString(), k + 1)
    }

    private fun rewriteInfix(src: String): String {
        if (src.indexOf("\\over") < 0 && src.indexOf("\\choose") < 0 && src.indexOf("\\atop") < 0) return src
        var depth = 0
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\\') {
                if (i + 1 < n && src[i + 1].isLetter()) {
                    var j = i + 1
                    while (j < n && src[j].isLetter()) j++
                    val nm = src.substring(i + 1, j)
                    if (depth == 0 && (nm == "over" || nm == "choose" || nm == "atop")) {
                        val cmd = if (nm == "over") "\\frac" else "\\binom"
                        return cmd + "{" + src.substring(0, i) + "}{" + src.substring(j) + "}"
                    }
                    i = j
                    continue
                }
                i += 2
                continue
            }
            if (c == '{') depth++ else if (c == '}') depth--
            i++
        }
        return src
    }

    private fun splitTop(s: String, rows: Boolean): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var env = 0
        var start = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                if (rows && depth == 0 && env == 0 && i + 1 < n && s[i + 1] == '\\') {
                    out.add(s.substring(start, i))
                    i += 2
                    if (i < n && s[i] == '[') {
                        val e = s.indexOf(']', i)
                        if (e > 0 && e - i <= 12) i = e + 1
                    }
                    start = i
                    continue
                }
                if (s.startsWith("\\begin", i)) env++
                else if (s.startsWith("\\end", i) && env > 0) env--
                i += 2
                continue
            }
            if (c == '{') depth++
            else if (c == '}') { if (depth > 0) depth-- }
            else if (!rows && c == '&' && depth == 0 && env == 0) {
                out.add(s.substring(start, i))
                start = i + 1
            }
            i++
        }
        out.add(s.substring(start))
        return out
    }

    private fun envText(env: String, bodyIn: String): String {
        var body = bodyIn
        if (env == "array" || env == "subarray" || env == "alignat" || env == "tabular") {
            val t = body.trimStart()
            if (t.startsWith("{")) {
                val sp = readGroup(t, 0)
                if (sp != null) body = t.substring(sp.next)
            }
        }
        val l: String
        val r: String
        when (env) {
            "pmatrix" -> { l = "("; r = ")" }
            "bmatrix" -> { l = "["; r = "]" }
            "Bmatrix" -> { l = "{"; r = "}" }
            "vmatrix" -> { l = "|"; r = "|" }
            "Vmatrix" -> { l = "‖"; r = "‖" }
            else -> { l = ""; r = "" }
        }
        val isCases = env == "cases" || env == "dcases" || env == "rcases"
        val lines = ArrayList<String>()
        for (row in splitTop(body, true)) {
            if (row.isBlank()) continue
            val cells = splitTop(row, false).map { latexToText(it, false).trim() }
            val text = cells.joinToString(if (isCases) ",  " else "  ")
            lines.add(if (l.isNotEmpty()) "$l $text $r" else text)
        }
        if (isCases) {
            for (k in lines.indices) lines[k] = (if (k == 0) "{ " else "  ") + lines[k]
        }
        return lines.joinToString("\n")
    }

    private val NOT_MAP: Map<String, String> = mapOf(
        "in" to "∉", "ni" to "∌", "subset" to "⊄", "supset" to "⊅", "subseteq" to "⊈", "supseteq" to "⊉",
        "equiv" to "≢", "sim" to "≁", "approx" to "≉", "cong" to "≇", "exists" to "∄", "leq" to "≰",
        "le" to "≰", "geq" to "≱", "ge" to "≱", "mid" to "∤", "parallel" to "∦", "prec" to "⊀",
        "succ" to "⊁", "vdash" to "⊬", "vDash" to "⊭", "simeq" to "≄", "eq" to "≠"
    )

    private fun latexToText(srcIn: String, bare: Boolean): String {
        val src = if (bare) srcIn else rewriteInfix(srcIn)
        val sb = StringBuilder()
        val n = src.length
        var i = 0
        while (i < n) {
            val c = src[i]
            if (c == '\\') {
                if (i + 1 >= n) { sb.append(c); i++; continue }
                val nx = src[i + 1]
                if (nx.isLetter()) {
                    var j = i + 1
                    while (j < n && src[j].isLetter()) j++
                    val name = src.substring(i + 1, j)
                    val r = handleCommand(name, src, j, bare)
                    if (r == null) {
                        sb.append(src, i, j)
                        i = j
                    } else {
                        sb.append(r.text)
                        i = r.next
                    }
                } else if (bare) {
                    sb.append(c)
                    i++
                } else {
                    when (nx) {
                        ',' -> sb.append('\u2009')
                        ';', ':', ' ' -> sb.append(' ')
                        '!' -> {}
                        '\\' -> sb.append(' ')
                        '{', '}', '%', '$', '&', '#', '_' -> sb.append(nx)
                        '|' -> sb.append('‖')
                        else -> { sb.append(c); sb.append(nx) }
                    }
                    i += 2
                }
                continue
            }
            if (c == '^' || c == '_') {
                val sup = c == '^'
                if (bare) {
                    if (i + 1 < n && src[i + 1] == '{') {
                        val g = readGroup(src, i + 1)
                        val mapped = g?.let { mapAll(it.inner, sup) }
                        if (g != null && mapped != null) {
                            sb.append(script(g.inner.replace(" ", ""), sup))
                            i = g.next
                            continue
                        }
                    }
                    sb.append(c)
                    i++
                    continue
                }
                val g = readGroup(src, i + 1)
                if (g == null) { sb.append(c); i++; continue }
                val arg = latexToText(g.inner, false)
                sb.append(script(arg, sup))
                i = g.next
                continue
            }
            if (c == '{' && !bare) {
                val g = readGroup(src, i)
                if (g != null) {
                    sb.append(latexToText(g.inner, false))
                    i = g.next
                    continue
                }
            }
            if (c == '}' && !bare) { i++; continue }
            if (c == '~' && !bare) { sb.append(' '); i++; continue }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun handleCommand(name: String, src: String, j: Int, bare: Boolean): R? {
        CustomMathSymbols.lookup(name)?.let { return R(it, j) }
        if (!bare && name in MathSymbolsExtra.LIMIT_OPS) {
            var k = j
            var lo = ""
            var up = ""
            var any = false
            for (round in 0..1) {
                var p = k
                while (p < src.length && src[p] == ' ') p++
                if (p < src.length && (src[p] == '_' || src[p] == '^')) {
                    val isUp = src[p] == '^'
                    val g = readGroup(src, p + 1) ?: break
                    val t = flat(latexToText(g.inner, false)).trim()
                    if (isUp) up = t else lo = t
                    k = g.next
                    any = true
                } else break
            }
            if (any) {
                val opText = when (name) {
                    "sum" -> "∑"; "prod" -> "∏"; "coprod" -> "∐"; "bigcup" -> "⋃"; "bigcap" -> "⋂"
                    "bigoplus" -> "⨁"; "bigotimes" -> "⨂"; "bigodot" -> "⨀"; "biguplus" -> "⨄"
                    "bigvee" -> "⋁"; "bigwedge" -> "⋀"; "bigsqcup" -> "⨆"
                    else -> name
                }
                return R(Mk.LM_OPEN.toString() + opText + Mk.LM_SEP + lo + Mk.LM_SEP + up + Mk.LM_CLOSE, k)
            }
        }
        when (name) {
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val a = readGroup(src, j) ?: return null
                val b = readGroup(src, a.next) ?: return null
                return R(fraction(latexToText(a.inner, bare), latexToText(b.inner, bare)), b.next)
            }
            "binom", "dbinom", "tbinom" -> {
                val a = readGroup(src, j) ?: return null
                val b = readGroup(src, a.next) ?: return null
                if (bare) return R("C(" + latexToText(a.inner, bare) + ", " + latexToText(b.inner, bare) + ")", b.next)
                val top = flat(latexToText(a.inner, bare)).trim()
                val bot = flat(latexToText(b.inner, bare)).trim()
                return R(Mk.BN_OPEN.toString() + top + Mk.BN_SEP + bot + Mk.BN_CLOSE, b.next)
            }
            "sqrt" -> {
                var k = j
                var index: String? = null
                if (k < src.length && src[k] == '[') {
                    val close = src.indexOf(']', k)
                    if (close > k) {
                        index = latexToText(src.substring(k + 1, close), bare)
                        k = close + 1
                    }
                }
                val g = readGroup(src, k) ?: return null
                val body = latexToText(g.inner, bare).trim()
                if (!bare) {
                    return R(Mk.SQ_OPEN.toString() + flat(index ?: "").trim() + Mk.SQ_SEP + flat(body) + Mk.SQ_CLOSE, g.next)
                }
                val idx: String = index ?: "2"
                val prefix = when (idx) {
                    "2" -> "√"
                    "3" -> "∛"
                    "4" -> "∜"
                    else -> (mapAll(idx, true) ?: "($idx)") + "√"
                }
                val simple = body.isNotEmpty() && body.all { it.isLetterOrDigit() || it == '.' }
                return R(if (simple) prefix + body else "$prefix($body)", g.next)
            }
            "mathcal", "mathscr" -> {
                val g = readGroup(src, j) ?: return null
                return R(latexToText(g.inner, bare).map { MathSymbolsExtra.CALLIGRAPHIC[it] ?: it }.joinToString(""), g.next)
            }
            "mathfrak" -> {
                val g = readGroup(src, j) ?: return null
                return R(latexToText(g.inner, bare).map { MathSymbolsExtra.FRAKTUR[it] ?: it }.joinToString(""), g.next)
            }
            "text", "mathrm", "mathbf", "mathit", "textbf", "textit", "textrm", "operatorname",
            "mbox", "boldsymbol", "mathsf", "mathtt", "bm", "emph" -> {
                val g = readGroup(src, j) ?: return null
                return R(latexToText(g.inner, bare), g.next)
            }
            "mathbb", "Bbb", "mathds", "mathbbm" -> {
                val g = readGroup(src, j) ?: return null
                val inner = g.inner.trim()
                val mapped = inner.map { (BLACKBOARD[it] ?: MathSymbolsExtra.BLACKBOARD[it])?.toString() ?: it.toString() }.joinToString("")
                return R(mapped, g.next)
            }
            "overline" -> {
                val g = readGroup(src, j) ?: return null
                val inner = latexToText(g.inner, bare)
                if (bare || inner.isEmpty()) return R(inner.map { it.toString() + '\u0305' }.joinToString(""), g.next)
                return R(Mk.OV_OPEN.toString() + flat(inner) + Mk.OV_CLOSE, g.next)
            }
            "sout", "cancel", "bcancel", "xcancel", "hcancel", "st", "strikethrough" -> {
                val g = readGroup(src, j) ?: return null
                val inner = flat(latexToText(g.inner, bare))
                if (inner.isEmpty()) return R("", g.next)
                return R("" + Mk.ST_OPEN + inner + Mk.ST_CLOSE, g.next)
            }
            "acute", "grave", "breve", "check", "mathring", "dddot", "ddddot", "overleftarrow", "overleftrightarrow" -> {
                val g = readGroup(src, j) ?: return null
                val inner = flat(latexToText(g.inner, bare))
                val mark = MathSymbolsExtra.ACCENTS[name] ?: return R(inner, g.next)
                val out = if (inner.isEmpty()) inner else inner + mark
                return R(out, g.next)
            }
            "vec", "hat", "bar", "tilde", "dot", "ddot", "underline", "widehat", "widetilde", "overrightarrow" -> {
                val g = readGroup(src, j) ?: return null
                val inner = latexToText(g.inner, bare)
                val mark = when (name) {
                    "vec", "overrightarrow" -> '\u20D7'
                    "hat", "widehat" -> '\u0302'
                    "bar" -> '\u0304'
                    "tilde", "widetilde" -> '\u0303'
                    "dot" -> '\u0307'
                    "ddot" -> '\u0308'
                    else -> '\u0332'
                }
                val withMark = if (name == "underline") {
                    inner.map { it.toString() + mark }.joinToString("")
                } else if (inner.isEmpty()) inner else inner + mark
                return R(withMark, g.next)
            }
            "boxed", "fbox", "framebox", "fcolorbox" -> {
                val g = readGroup(src, j) ?: return null
                return R("[" + latexToText(g.inner, bare) + "]", g.next)
            }
            "pmod", "pod" -> {
                val g = readGroup(src, j) ?: return null
                val inner = latexToText(g.inner, bare).trim()
                return R(if (name == "pmod") " (mod $inner)" else " ($inner)", g.next)
            }
            "bmod" -> return R(" mod ", j)
            "eqref" -> {
                val g = readGroup(src, j) ?: return null
                return R("(" + g.inner.trim() + ")", g.next)
            }
            "underbrace", "overbrace", "mathop", "mathrel", "mathbin", "mathord", "mathpunct",
            "mathclose", "mathopen", "textnormal", "textup", "textsc", "textsl", "textmd", "mathnormal",
            "unicode", "displaymath", "hphantom", "smash", "mathstrut", "vcenter", "raisebox", "makebox" -> {
                val g = readGroup(src, j) ?: return null
                if (name == "hphantom") return R("", g.next)
                return R(latexToText(g.inner, bare), g.next)
            }
            "phantom", "vphantom", "label", "hspace", "vspace", "hskip", "vskip", "color", "pagecolor",
            "tag", "mspace", "kern", "mkern" -> {
                val g = readGroup(src, j) ?: return null
                if (name == "tag") return R("  (" + latexToText(g.inner, bare).trim() + ")", g.next)
                return R("", g.next)
            }
            "textcolor", "colorbox" -> {
                val c = readGroup(src, j) ?: return null
                val g = readGroup(src, c.next) ?: return null
                return R(latexToText(g.inner, bare), g.next)
            }
            "textsuperscript", "textsubscript" -> {
                if (bare) return null
                val g = readGroup(src, j) ?: return null
                return R(script(flat(latexToText(g.inner, bare)), name == "textsuperscript"), g.next)
            }
            "stackrel", "overset", "underset" -> {
                if (bare) return null
                val a = readGroup(src, j) ?: return null
                val b = readGroup(src, a.next) ?: return null
                val top = flat(latexToText(a.inner, bare))
                val base = latexToText(b.inner, bare)
                return R(base + script(top, name != "underset"), b.next)
            }
            "xrightarrow", "xleftarrow", "xRightarrow", "xLeftarrow", "xleftrightarrow", "xLeftrightarrow",
            "xmapsto", "xhookrightarrow" -> {
                if (bare) return null
                var k = j
                var below = ""
                if (k < src.length && src[k] == '[') {
                    val close = src.indexOf(']', k)
                    if (close > k) {
                        below = flat(latexToText(src.substring(k + 1, close), false)).trim()
                        k = close + 1
                    }
                }
                val g = readGroup(src, k) ?: return null
                val above = flat(latexToText(g.inner, false)).trim()
                val (lh, rh) = when (name) {
                    "xrightarrow" -> "─" to "→"
                    "xleftarrow" -> "←" to "─"
                    "xRightarrow" -> "═" to "⇒"
                    "xLeftarrow" -> "⇐" to "═"
                    "xleftrightarrow" -> "←" to "→"
                    "xLeftrightarrow" -> "⇐" to "⇒"
                    "xmapsto" -> "↦" to "→"
                    else -> "↪" to "─"
                }
                val mid = if (above.isEmpty()) "─" else "─$above─"
                val suffix = if (below.isEmpty()) "" else script(below, false)
                return R(lh + mid + rh + suffix, g.next)
            }
            "substack", "shortstack", "Longstack" -> {
                val g = readGroup(src, j) ?: return null
                return R(latexToText(g.inner.replace("\\\\", ", "), bare).trim(), g.next)
            }
            "not" -> {
                if (bare) return null
                var k = j
                while (k < src.length && src[k] == ' ') k++
                if (k < src.length && src[k] == '\\') {
                    var e = k + 1
                    while (e < src.length && src[e].isLetter()) e++
                    val nm = src.substring(k + 1, e)
                    val neg = NOT_MAP[nm]
                    if (neg != null) return R(neg, e)
                } else if (k < src.length) {
                    val neg = when (src[k]) { '=' -> "≠"; '<' -> "≮"; '>' -> "≯"; else -> null }
                    if (neg != null) return R(neg, k + 1)
                }
                return null
            }
            "begin" -> {
                if (bare) return null
                val eg = readGroup(src, j) ?: return null
                val envName = eg.inner.trim()
                val endTag = "\\end{" + eg.inner + "}"
                val e = src.indexOf(endTag, eg.next)
                if (e < 0) return null
                val body = src.substring(eg.next, e)
                return R(envText(envName.removeSuffix("*"), body), e + endTag.length)
            }
            "end" -> {
                if (bare) return null
                val eg = readGroup(src, j) ?: return null
                return R("", eg.next)
            }
            "left", "right", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
            "big", "Big", "bigg", "Bigg" -> {
                var k = j
                if (k < src.length && src[k] == '.') k++
                return R("", k)
            }
            "displaystyle", "textstyle", "scriptstyle", "limits", "nolimits", "nonumber", "notag" -> return R("", j)
            "quad" -> return R("  ", j)
            "qquad" -> return R("    ", j)
            "newline" -> return R("\n", j)
        }
        if ((name in MathSymbolsExtra.FUNCTIONS || name in MathSymbolsMore.FUNCTIONS) && SYMBOLS[name] == null) return R(name, j)
        val sym = SYMBOLS[name] ?: MathSymbolsExtra.SYMBOLS[name] ?: MathSymbolsMore.SYMBOLS[name] ?: return null
        return R(sym, j)
    }

    private fun fraction(a: String, b: String): String {
        val na = a.trim()
        val nb = b.trim()
        VULGAR["$na/$nb"]?.let { return it }
        return Mk.FR_OPEN.toString() + flat(na) + Mk.FR_SEP + flat(nb) + Mk.FR_CLOSE
    }

    private fun flat(s: String): String {
        var hasBlock = false
        for (ch in s) if (Mk.isBlockOpen(ch) || ch == Mk.ST_OPEN || ch == Mk.ST_CLOSE) { hasBlock = true; break }
        if (!hasBlock) return s
        return s.replace(Mk.ST_OPEN.toString(), "").replace(Mk.ST_CLOSE.toString(), "")
            .replace(Mk.FR_OPEN.toString(), "(").replace(Mk.FR_SEP.toString(), ")/(").replace(Mk.FR_CLOSE.toString(), ")")
            .replace(Mk.SQ_OPEN.toString(), "√(").replace(Mk.SQ_SEP.toString(), "").replace(Mk.SQ_CLOSE.toString(), ")")
            .replace(Mk.OV_OPEN.toString(), "").replace(Mk.OV_CLOSE.toString(), "")
            .replace(Mk.BN_OPEN.toString(), "C(").replace(Mk.BN_SEP.toString(), ", ").replace(Mk.BN_CLOSE.toString(), ")")
            .replace(Mk.LM_OPEN.toString(), "").replace(Mk.LM_SEP.toString(), " ").replace(Mk.LM_CLOSE.toString(), "")
    }

    private fun flattenFractions(s: String): String {
        if (s.indexOf(Mk.FR_OPEN) < 0) return s
        return s.replace(Mk.FR_OPEN.toString(), "(")
            .replace(Mk.FR_SEP.toString(), ")/(")
            .replace(Mk.FR_CLOSE.toString(), ")")
    }

    private fun wrapIfNeeded(s: String): String {
        if (s.isEmpty()) return s
        val needs = s.any { it.isWhitespace() || it in "+-−±×÷=<>≤≥≠≈/" }
        return if (needs) "($s)" else s
    }

    private fun mapAll(s: String, sup: Boolean): String? {
        if (s.isEmpty()) return null
        val map = if (sup) SUP else SUB
        val sb = StringBuilder()
        for (ch in s) {
            if (ch == ' ') continue
            sb.append(map[ch] ?: return null)
        }
        return sb.toString()
    }

    private fun script(arg: String, sup: Boolean): String {
        val a = arg.trim()
        if (a.isEmpty()) return ""
        if (sup && a in PASSTHROUGH_SUP) return a
        return if (sup) Mk.SUP_OPEN + a + Mk.SUP_CLOSE else Mk.SUB_OPEN + a + Mk.SUB_CLOSE
    }

    private val PASSTHROUGH_SUP = setOf("°", "′", "″", "∘", "∗", "†", "⊤", "*")

    private val VULGAR = mapOf("1/2" to "½", "1/4" to "¼", "3/4" to "¾")

    private val SUP: Map<Char, Char> = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵',
        '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'f' to 'ᶠ', 'g' to 'ᵍ', 'h' to 'ʰ',
        'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ',
        't' to 'ᵗ', 'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ'
    )

    private val SUB: Map<Char, Char> = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
        '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'l' to 'ₗ',
        'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ',
        'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ', 'β' to 'ᵦ', 'γ' to 'ᵧ', 'ρ' to 'ᵨ', 'φ' to 'ᵩ', 'χ' to 'ᵪ'
    )

    private val BLACKBOARD: Map<Char, Char> = mapOf(
        'R' to 'ℝ', 'N' to 'ℕ', 'Z' to 'ℤ', 'Q' to 'ℚ', 'C' to 'ℂ', 'P' to 'ℙ', 'H' to 'ℍ'
    )

    private val SYMBOLS: Map<String, String> = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗",
        "star" to "⋆", "circ" to "∘", "bullet" to "•", "oplus" to "⊕", "otimes" to "⊗", "ominus" to "⊖",
        "cup" to "∪", "cap" to "∩", "setminus" to "∖", "wedge" to "∧", "land" to "∧", "vee" to "∨",
        "lor" to "∨", "neg" to "¬", "lnot" to "¬",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝",
        "ll" to "≪", "gg" to "≫", "mid" to "∣", "parallel" to "∥", "perp" to "⊥",
        "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "emptyset" to "∅", "varnothing" to "∅",
        "sum" to "∑", "prod" to "∏", "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
        "partial" to "∂", "nabla" to "∇", "infty" to "∞", "angle" to "∠", "degree" to "°",
        "prime" to "′", "forall" to "∀", "exists" to "∃", "nexists" to "∄", "therefore" to "∴",
        "because" to "∵", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
        "top" to "⊤", "bot" to "⊥",
        "leqslant" to "≤", "geqslant" to "≥", "lt" to "<", "gt" to ">", "nless" to "≮", "ngtr" to "≯",
        "nleq" to "≰", "ngeq" to "≱", "doteq" to "≐", "nmid" to "∤", "subsetneq" to "⊊", "supsetneq" to "⊋",
        "nsubseteq" to "⊈", "bigcup" to "⋃", "bigcap" to "⋂", "bigoplus" to "⨁", "bigotimes" to "⨂",
        "uplus" to "⊎", "sqcup" to "⊔", "sqcap" to "⊓", "wp" to "℘", "imath" to "ı", "jmath" to "ȷ",
        "triangle" to "△", "square" to "□", "blacksquare" to "■", "checkmark" to "✓", "dagger" to "†",
        "ddagger" to "‡", "copyright" to "©", "euro" to "€", "pounds" to "£", "textdegree" to "°",
        "lbrack" to "[", "rbrack" to "]", "colon" to ":",
        "hookrightarrow" to "↪", "hookleftarrow" to "↩", "rightleftharpoons" to "⇌", "longleftrightarrow" to "⟷",
        "Longrightarrow" to "⟹", "Longleftarrow" to "⟸", "Longleftrightarrow" to "⟺", "nearrow" to "↗",
        "searrow" to "↘", "swarrow" to "↙", "nwarrow" to "↖", "updownarrow" to "↕", "Uparrow" to "⇑",
        "Downarrow" to "⇓", "twoheadrightarrow" to "↠", "leadsto" to "⇝",
        "Alpha" to "Α", "Beta" to "Β", "Epsilon" to "Ε", "Zeta" to "Ζ", "Eta" to "Η", "Iota" to "Ι",
        "Kappa" to "Κ", "Mu" to "Μ", "Nu" to "Ν", "Omicron" to "Ο", "omicron" to "ο", "Rho" to "Ρ",
        "Tau" to "Τ", "Chi" to "Χ",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "leftrightarrow" to "↔",
        "Rightarrow" to "⇒", "Leftarrow" to "⇐", "Leftrightarrow" to "⇔", "implies" to "⟹",
        "impliedby" to "⟸", "iff" to "⟺", "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
        "longrightarrow" to "⟶", "longleftarrow" to "⟵",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉",
        "lbrace" to "{", "rbrace" to "}", "vert" to "|", "lvert" to "|", "rvert" to "|", "Vert" to "‖",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
        "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ",
        "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ",
        "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
        "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω"
    )
}
