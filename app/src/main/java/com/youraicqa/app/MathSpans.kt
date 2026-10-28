package com.youraicqa.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.ReplacementSpan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

object Mk {
    const val FR_OPEN = '\uE020'
    const val FR_SEP = '\uE021'
    const val FR_CLOSE = '\uE022'
    const val SUP_OPEN = '\uE023'
    const val SUP_CLOSE = '\uE024'
    const val SUB_OPEN = '\uE025'
    const val SUB_CLOSE = '\uE026'

    const val SQ_OPEN = '\uE027'
    const val SQ_SEP = '\uE028'
    const val SQ_CLOSE = '\uE029'
    const val OV_OPEN = '\uE02A'
    const val OV_CLOSE = '\uE02B'
    const val BN_OPEN = '\uE02C'
    const val BN_SEP = '\uE02D'
    const val BN_CLOSE = '\uE02E'
    const val LM_OPEN = '\uE02F'
    const val LM_SEP = '\uE030'
    const val LM_CLOSE = '\uE031'

    const val ST_OPEN = '\uE032'
    const val ST_CLOSE = '\uE033'

    fun isMarker(c: Char): Boolean = c in '\uE020'..'\uE033'

    fun isBlockOpen(c: Char): Boolean =
        c == FR_OPEN || c == SQ_OPEN || c == OV_OPEN || c == BN_OPEN || c == LM_OPEN

    fun closeOf(open: Char): Char = when (open) {
        FR_OPEN -> FR_CLOSE
        SQ_OPEN -> SQ_CLOSE
        OV_OPEN -> OV_CLOSE
        BN_OPEN -> BN_CLOSE
        LM_OPEN -> LM_CLOSE
        SUP_OPEN -> SUP_CLOSE
        else -> SUB_CLOSE
    }

    private fun wrapFlat(s: String): String =
        if (s.any { it.isWhitespace() || it in "+-−±×÷=<>≤≥≠≈/" }) "($s)" else s

    fun flatOf(open: Char, inner: String): String {
        when (open) {
            FR_OPEN -> {
                val sep = inner.indexOf(FR_SEP)
                if (sep < 0) return inner
                return wrapFlat(inner.substring(0, sep)) + "/" + wrapFlat(inner.substring(sep + 1))
            }
            SQ_OPEN -> {
                val sep = inner.indexOf(SQ_SEP)
                val idx = if (sep > 0) plain(inner.substring(0, sep)) else ""
                val body = if (sep >= 0) inner.substring(sep + 1) else inner
                val prefix = when (idx) { "", "2" -> "√"; "3" -> "∛"; "4" -> "∜"; else -> "$idx√" }
                val pb = plain(body)
                val simple = pb.isNotEmpty() && pb.all { it.isLetterOrDigit() || it == '.' }
                return if (simple) prefix + body else "$prefix($body)"
            }
            OV_OPEN -> return inner
            BN_OPEN -> {
                val sep = inner.indexOf(BN_SEP)
                if (sep < 0) return inner
                return "C(" + inner.substring(0, sep) + "," + inner.substring(sep + 1) + ")"
            }
            LM_OPEN -> {
                val parts = inner.split(LM_SEP)
                val op = parts.getOrElse(0) { "" }
                val lo = parts.getOrElse(1) { "" }
                val up = parts.getOrElse(2) { "" }
                return op + (if (lo.isNotEmpty()) "_" + wrapFlat(lo) else "") +
                    (if (up.isNotEmpty()) "^" + wrapFlat(up) else "")
            }
        }
        return inner
    }

    fun plain(s: String): String {
        var has = false
        for (c in s) if (isMarker(c)) { has = true; break }
        if (!has) return s
        val sb = StringBuilder(s.length)
        for (c in s) if (!isMarker(c)) sb.append(c)
        return sb.toString()
    }
}

object MathSpans {
    fun apply(src: SpannableString): SpannableString {
        val raw = src.toString()
        var any = false
        for (c in raw) if (Mk.isMarker(c)) { any = true; break }
        if (!any) return src
        val sb = SpannableStringBuilder(src)
        var guard = 0
        var phase = 1
        while (guard++ < 5000) {
            val text = sb.toString()
            var o = -1
            for (i in text.length - 1 downTo 0) {
                val c = text[i]
                val hit = if (phase == 1) Mk.isBlockOpen(c) else (c == Mk.SUP_OPEN || c == Mk.SUB_OPEN)
                if (hit) { o = i; break }
            }
            if (o < 0) {
                if (phase == 1) { phase = 2; continue }
                break
            }
            val kind = text[o]
            val closeCh = Mk.closeOf(kind)
            val close = text.indexOf(closeCh, o + 1)
            if (close < 0) { sb.delete(o, o + 1); continue }
            val inner = text.substring(o + 1, close)
            if (kind == Mk.FR_OPEN) {
                val sep = inner.indexOf(Mk.FR_SEP)
                if (sep < 0) { sb.replace(o, close + 1, Mk.plain(inner)); continue }
                val num = inner.substring(0, sep)
                val den = inner.substring(sep + 1)
                val flat = wrap(Mk.plain(num)) + "/" + wrap(Mk.plain(den))
                sb.replace(o, close + 1, flat)
                sb.setSpan(FractionSpan(num, den), o, o + flat.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (kind != Mk.SUP_OPEN && kind != Mk.SUB_OPEN) {
                applyBlock(sb, kind, o, close, inner)
            } else {
                val flat = Mk.plain(inner)
                sb.replace(o, close + 1, flat)
                if (flat.isNotEmpty()) {
                    sb.setSpan(
                        if (kind == Mk.SUP_OPEN) SuperscriptSpan() else SubscriptSpan(),
                        o, o + flat.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(RelativeSizeSpan(0.72f), o, o + flat.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        var strikeGuard = 0
        while (strikeGuard++ < 5000) {
            val text = sb.toString()
            val c = text.indexOf(Mk.ST_CLOSE)
            if (c < 0) break
            val o = text.lastIndexOf(Mk.ST_OPEN, c)
            if (o < 0) { sb.delete(c, c + 1); continue }
            sb.delete(c, c + 1)
            sb.delete(o, o + 1)
            if (c - 1 > o) {
                sb.setSpan(android.text.style.StrikethroughSpan(), o, c - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        for (i in sb.length - 1 downTo 0) if (Mk.isMarker(sb[i])) sb.delete(i, i + 1)
        return SpannableString(sb)
    }

    private fun applyBlock(sb: SpannableStringBuilder, kind: Char, o: Int, close: Int, inner: String) {
        val flatRaw = try { Mk.flatOf(kind, inner) } catch (e: Exception) { inner }
        val flat = Mk.plain(flatRaw).replace(' ', '\u00A0')
        val span: Any? = try {
            when (kind) {
                Mk.SQ_OPEN -> {
                    val sep = inner.indexOf(Mk.SQ_SEP)
                    if (sep < 0) null else BarSpan(inner.substring(sep + 1), Mk.plain(inner.substring(0, sep)), true)
                }
                Mk.OV_OPEN -> BarSpan(inner, "", false)
                Mk.BN_OPEN -> {
                    val sep = inner.indexOf(Mk.BN_SEP)
                    if (sep < 0) null else BinomSpan(inner.substring(0, sep), inner.substring(sep + 1))
                }
                Mk.LM_OPEN -> {
                    val parts = inner.split(Mk.LM_SEP)
                    if (parts.size < 3 || parts[0].isEmpty()) null else LimitsSpan(parts[0], parts[1], parts[2])
                }
                else -> null
            }
        } catch (e: Exception) { null }
        sb.replace(o, close + 1, flat)
        if (span != null && flat.isNotEmpty()) {
            sb.setSpan(span, o, o + flat.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun wrap(s: String): String =
        if (s.any { it.isWhitespace() || it in "+-−±×÷=<>≤≥≠≈/" }) "($s)" else s
}

class FractionSpan(private val num: String, private val den: String) : ReplacementSpan() {
    private val scale = 0.8f

    private fun small(base: Paint): Paint = Paint(base).apply { textSize = base.textSize * scale }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val sp = small(paint)
        val pad = paint.textSize * 0.14f
        val w = max(measureRich(num, sp), measureRich(den, sp))
        if (fm != null) {
            val g = geom(paint, sp)
            val needAscent = -ceil(g.numTop).toInt()
            val needDescent = ceil(g.denBottom).toInt()
            if (needAscent < fm.ascent) fm.ascent = needAscent
            if (needAscent < fm.top) fm.top = needAscent
            if (needDescent > fm.descent) fm.descent = needDescent
            if (needDescent > fm.bottom) fm.bottom = needDescent
        }
        return ceil(w + pad * 2).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val sp = small(paint)
        val pad = paint.textSize * 0.14f
        val wn = measureRich(num, sp)
        val wd = measureRich(den, sp)
        val w = max(wn, wd)
        val g = geom(paint, sp)
        val inner = x + pad
        drawRich(canvas, num, inner + (w - wn) / 2f, y - g.numBase, sp)
        drawRich(canvas, den, inner + (w - wd) / 2f, y + g.denBase, sp)
        val bar = Paint(paint).apply { strokeWidth = max(1f, paint.textSize * 0.065f); style = Paint.Style.STROKE }
        val ay = y - g.axis
        canvas.drawLine(x + pad * 0.4f, ay, x + pad * 1.6f + w, ay, bar)
    }

    private class Geom(val axis: Float, val numBase: Float, val numTop: Float, val denBase: Float, val denBottom: Float)

    private fun geom(main: Paint, small: Paint): Geom {
        val axis = main.textSize * 0.28f
        val gap = main.textSize * 0.1f
        val cap = small.textSize * 0.78f
        val desc = small.textSize * 0.22f
        val numBase = axis + gap + desc
        val numTop = numBase + cap
        val denBase = gap - axis + cap
        val denBottom = denBase + desc
        return Geom(axis, numBase, numTop, denBase, denBottom)
    }

    companion object {
        private const val SCRIPT = 0.7f
        private const val SUP_SHIFT = 0.38f
        private const val SUB_SHIFT = 0.16f

        fun measureRich(s: String, paint: Paint): Float {
            var width = 0f
            val seg = StringBuilder()
            var depth = 0
            val p = Paint(paint)
            fun flush() {
                if (seg.isNotEmpty()) { width += p.measureText(seg.toString()); seg.setLength(0) }
            }
            for (c in s) {
                when (c) {
                    Mk.SUP_OPEN, Mk.SUB_OPEN -> { flush(); depth++; p.textSize = paint.textSize * Math.pow(SCRIPT.toDouble(), depth.toDouble()).toFloat() }
                    Mk.SUP_CLOSE, Mk.SUB_CLOSE -> { flush(); if (depth > 0) depth--; p.textSize = paint.textSize * Math.pow(SCRIPT.toDouble(), depth.toDouble()).toFloat() }
                    else -> if (!Mk.isMarker(c)) seg.append(c)
                }
            }
            flush()
            return width
        }

        fun drawRich(canvas: Canvas, s: String, x0: Float, baseline: Float, paint: Paint) {
            var x = x0
            val seg = StringBuilder()
            val p = Paint(paint)
            var depth = 0
            var shift = 0f
            val shifts = ArrayDeque<Float>()
            fun flush() {
                if (seg.isNotEmpty()) {
                    canvas.drawText(seg.toString(), x, baseline - shift, p)
                    x += p.measureText(seg.toString())
                    seg.setLength(0)
                }
            }
            for (c in s) {
                when (c) {
                    Mk.SUP_OPEN, Mk.SUB_OPEN -> {
                        flush()
                        shifts.addLast(shift)
                        shift += if (c == Mk.SUP_OPEN) paint.textSize * SUP_SHIFT else -paint.textSize * SUB_SHIFT
                        depth++
                        p.textSize = paint.textSize * Math.pow(SCRIPT.toDouble(), depth.toDouble()).toFloat()
                    }
                    Mk.SUP_CLOSE, Mk.SUB_CLOSE -> {
                        flush()
                        if (depth > 0) depth--
                        shift = if (shifts.isNotEmpty()) shifts.removeLast() else 0f
                        p.textSize = paint.textSize * Math.pow(SCRIPT.toDouble(), depth.toDouble()).toFloat()
                    }
                    else -> if (!Mk.isMarker(c)) seg.append(c)
                }
            }
            flush()
        }
    }
}

class BarSpan(private val body: String, private val index: String, private val radical: Boolean) : ReplacementSpan() {
    private class L(
        val lead: Float, val rad: Float, val bodyW: Float, val barH: Float,
        val below: Float, val pad: Float, val indexW: Float
    )

    private fun layout(paint: Paint): L {
        val ts = paint.textSize
        val bodyW = FractionSpan.measureRich(body, paint)
        val idxP = Paint(paint).apply { textSize = ts * 0.5f }
        val indexW = if (radical && index.isNotEmpty()) FractionSpan.measureRich(index, idxP) else 0f
        val rad = if (radical) ts * 0.55f else 0f
        val lead = if (indexW > rad * 0.4f) indexW - rad * 0.4f else 0f
        val hasSup = body.indexOf(Mk.SUP_OPEN) >= 0
        val hasSub = body.indexOf(Mk.SUB_OPEN) >= 0
        val barH = ts * (if (hasSup) 1.08f else if (radical) 0.92f else 0.8f)
        val below = ts * (if (hasSub) 0.32f else if (radical) 0.14f else 0f)
        return L(lead, rad, bodyW, barH, below, ts * 0.05f, indexW)
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val l = layout(paint)
        if (fm != null) {
            val needAscent = -ceil(l.barH + paint.textSize * 0.08f).toInt()
            val needDescent = ceil(l.below).toInt()
            if (needAscent < fm.ascent) fm.ascent = needAscent
            if (needAscent < fm.top) fm.top = needAscent
            if (needDescent > fm.descent) fm.descent = needDescent
            if (needDescent > fm.bottom) fm.bottom = needDescent
        }
        return ceil(l.lead + l.rad + l.bodyW + l.pad * 2).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val l = layout(paint)
        val ts = paint.textSize
        val line = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, ts * 0.065f)
            isAntiAlias = true
        }
        val base = y.toFloat()
        val barY = base - l.barH
        val px = x + l.pad + l.lead
        val bx = px + l.rad
        if (radical) {
            val p = Path()
            p.moveTo(px, base - ts * 0.30f)
            p.lineTo(px + ts * 0.10f, base - ts * 0.37f)
            p.lineTo(px + ts * 0.27f, base + l.below * 0.9f)
            p.lineTo(bx, barY)
            p.lineTo(bx + l.bodyW + l.pad, barY)
            canvas.drawPath(p, line)
            if (index.isNotEmpty()) {
                val ip = Paint(paint).apply { textSize = ts * 0.5f }
                FractionSpan.drawRich(canvas, index, x + l.pad, base - ts * 0.52f, ip)
            }
        } else {
            canvas.drawLine(bx, barY, bx + l.bodyW, barY, line)
        }
        FractionSpan.drawRich(canvas, body, bx, base, Paint(paint))
    }
}

class BinomSpan(private val top: String, private val bot: String) : ReplacementSpan() {
    private val scale = 0.8f

    private class G(val numBase: Float, val numTop: Float, val denBase: Float, val denBottom: Float)

    private fun geom(main: Paint, sm: Paint): G {
        val axis = main.textSize * 0.28f
        val gap = main.textSize * 0.1f
        val cap = sm.textSize * 0.78f
        val desc = sm.textSize * 0.22f
        val numBase = axis + gap + desc
        val denBase = gap - axis + cap
        return G(numBase, numBase + cap, denBase, denBase + desc)
    }

    private fun small(base: Paint): Paint = Paint(base).apply { textSize = base.textSize * scale }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val sp = small(paint)
        val w = max(FractionSpan.measureRich(top, sp), FractionSpan.measureRich(bot, sp))
        if (fm != null) {
            val g = geom(paint, sp)
            val needAscent = -ceil(g.numTop).toInt()
            val needDescent = ceil(g.denBottom).toInt()
            if (needAscent < fm.ascent) fm.ascent = needAscent
            if (needAscent < fm.top) fm.top = needAscent
            if (needDescent > fm.descent) fm.descent = needDescent
            if (needDescent > fm.bottom) fm.bottom = needDescent
        }
        val pw = paint.textSize * 0.28f
        val pad = paint.textSize * 0.06f
        return ceil(w + pw * 2 + pad * 2).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val sp = small(paint)
        val wt = FractionSpan.measureRich(this.top, sp)
        val wb = FractionSpan.measureRich(bot, sp)
        val w = max(wt, wb)
        val g = geom(paint, sp)
        val pw = paint.textSize * 0.28f
        val pad = paint.textSize * 0.06f
        val base = y.toFloat()
        val cx = x + pad + pw
        FractionSpan.drawRich(canvas, this.top, cx + (w - wt) / 2f, base - g.numBase, sp)
        FractionSpan.drawRich(canvas, bot, cx + (w - wb) / 2f, base + g.denBase, sp)
        val line = Paint(paint).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, paint.textSize * 0.06f)
            isAntiAlias = true
        }
        val yTop = base - g.numTop
        val yBot = base + g.denBottom
        val yMid = (yTop + yBot) / 2f
        val left = Path()
        left.moveTo(cx, yTop)
        left.quadTo(x + pad - pw * 0.4f, yMid, cx, yBot)
        canvas.drawPath(left, line)
        val rx = cx + w
        val right = Path()
        right.moveTo(rx, yTop)
        right.quadTo(rx + pw * 1.4f, yMid, rx, yBot)
        canvas.drawPath(right, line)
    }
}

class LimitsSpan(private val op: String, private val lo: String, private val up: String) : ReplacementSpan() {
    private val isText = op.isNotEmpty() && op[0].isLetter()

    private class L(
        val w: Float, val opW: Float, val opDy: Float, val upDy: Float, val loDy: Float,
        val top: Float, val bottom: Float
    )

    private fun opPaint(paint: Paint): Paint =
        Paint(paint).apply { textSize = paint.textSize * (if (isText) 1f else 1.35f) }

    private fun smallPaint(paint: Paint): Paint = Paint(paint).apply { textSize = paint.textSize * 0.72f }

    private fun layout(paint: Paint): L {
        val ts = paint.textSize
        val opP = opPaint(paint)
        val sm = smallPaint(paint)
        val b = Rect()
        opP.getTextBounds(op, 0, op.length, b)
        val opW = opP.measureText(op)
        val axis = ts * 0.28f
        val opDy = if (isText) 0f else -axis - (b.top + b.bottom) / 2f
        val glyphTop = opDy + b.top
        val glyphBottom = opDy + b.bottom
        val gap = ts * 0.06f
        val upDy = glyphTop - gap - sm.textSize * 0.22f
        val loDy = glyphBottom + gap + sm.textSize * 0.78f
        val upW = if (up.isNotEmpty()) FractionSpan.measureRich(up, sm) else 0f
        val loW = if (lo.isNotEmpty()) FractionSpan.measureRich(lo, sm) else 0f
        var topY = minOf(glyphTop, 0f)
        var botY = maxOf(glyphBottom, 0f)
        if (up.isNotEmpty()) topY = minOf(topY, upDy - sm.textSize * (if (up.indexOf(Mk.SUP_OPEN) >= 0) 1.1f else 0.78f))
        if (lo.isNotEmpty()) botY = maxOf(botY, loDy + sm.textSize * (if (lo.indexOf(Mk.SUB_OPEN) >= 0) 0.4f else 0.22f))
        return L(max(opW, max(upW, loW)), opW, opDy, upDy, loDy, topY, botY)
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val l = layout(paint)
        if (fm != null) {
            val needAscent = floor(l.top).toInt()
            val needDescent = ceil(l.bottom).toInt()
            if (needAscent < fm.ascent) fm.ascent = needAscent
            if (needAscent < fm.top) fm.top = needAscent
            if (needDescent > fm.descent) fm.descent = needDescent
            if (needDescent > fm.bottom) fm.bottom = needDescent
        }
        return ceil(l.w + paint.textSize * 0.16f).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val l = layout(paint)
        val sm = smallPaint(paint)
        val base = y.toFloat()
        val inner = x + paint.textSize * 0.08f
        canvas.drawText(op, inner + (l.w - l.opW) / 2f, base + l.opDy, opPaint(paint))
        if (up.isNotEmpty()) {
            val w = FractionSpan.measureRich(up, sm)
            FractionSpan.drawRich(canvas, up, inner + (l.w - w) / 2f, base + l.upDy, sm)
        }
        if (lo.isNotEmpty()) {
            val w = FractionSpan.measureRich(lo, sm)
            FractionSpan.drawRich(canvas, lo, inner + (l.w - w) / 2f, base + l.loDy, sm)
        }
    }
}

object StrikeMd {
    private val TAGS = arrayOf("", "~~", "<s>", "<del>", "<strike>")
    private val CLOSE_TAGS = arrayOf("", "~~", "</s>", "</del>", "</strike>")

    fun openKind(s: String, i: Int): Int {
        if (i < 0 || i >= s.length) return 0
        val c = s[i]
        if (c != '~' && c != '<') return 0
        for (k in 1..4) {
            val t = TAGS[k]
            if (!s.regionMatches(i, t, 0, t.length, ignoreCase = true)) continue
            if (k == 1) {
                if (i > 0 && s[i - 1] == '~') return 0
                val nx = i + 2
                if (nx >= s.length || s[nx] == '~' || s[nx].isWhitespace()) return 0
            }
            return if (findClose(s, i + t.length, k) >= 0) k else 0
        }
        return 0
    }

    fun openLen(kind: Int): Int = if (kind in 1..4) TAGS[kind].length else 0

    fun closeLen(s: String, i: Int, kind: Int): Int {
        if (kind !in 1..4 || i < 0 || i >= s.length) return 0
        val t = CLOSE_TAGS[kind]
        if (!s.regionMatches(i, t, 0, t.length, ignoreCase = true)) return 0
        if (kind == 1) {
            if (i == 0 || s[i - 1].isWhitespace() || s[i - 1] == '~') return 0
            if (i + 2 < s.length && s[i + 2] == '~') return 0
        }
        return t.length
    }

    fun findClose(s: String, from: Int, kind: Int): Int {
        var inCode = false
        var j = from
        while (j < s.length) {
            val ch = s[j]
            if (ch == '`') inCode = !inCode
            else if (!inCode && closeLen(s, j, kind) > 0 && (kind != 1 || j > from)) return j
            j++
        }
        return -1
    }
}
