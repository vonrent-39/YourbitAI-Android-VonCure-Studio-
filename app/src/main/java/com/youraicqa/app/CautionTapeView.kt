package com.youraicqa.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class CautionTapeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val yellow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF2BE00.toInt() }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF14130F.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40000000 }
    private val clip = Path()
    private val stripePath = Path()
    var cornerDp: Float = 16f

    private class Tape(val cx: Float, val cy: Float, val angle: Float, val thicknessDp: Float, val phase: Float)

    private var tapes: List<Tape> = generate(System.nanoTime())

    fun reshuffle() {
        tapes = generate(System.nanoTime())
        invalidate()
    }

    private fun generate(seed: Long): List<Tape> {
        val r = java.util.Random(seed)
        val n = 2 + r.nextInt(3)
        val slots = (0 until n).map { 0.16f + 0.68f * (it + 0.5f) / n }.shuffled(kotlin.random.Random(seed))
        var sign = if (r.nextBoolean()) 1f else -1f
        return slots.map { y ->
            sign = -sign
            val flip = if (r.nextInt(4) == 0) -1f else 1f
            val angle = sign * flip * (3f + r.nextFloat() * 10f)
            Tape(
                cx = 0.38f + r.nextFloat() * 0.24f,
                cy = (y + (r.nextFloat() - 0.5f) * 0.10f).coerceIn(0.1f, 0.9f),
                angle = angle,
                thicknessDp = 14f + r.nextFloat() * 11f,
                phase = r.nextFloat()
            )
        }
    }

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val w = if (wMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else 0
        val h = if (hMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, w, h), cornerDp * d, cornerDp * d, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        for (t in tapes) tape(c, w, h, t)
        c.restore()
    }

    private fun tape(c: Canvas, w: Float, h: Float, t: Tape) {
        val th = t.thicknessDp * d
        val len = w * 1.7f
        c.save()
        c.translate(w * t.cx, h * t.cy)
        c.rotate(t.angle)
        val r = RectF(-len / 2f, -th / 2f, len / 2f, th / 2f)
        c.drawRect(r.left, r.top + 2f * d, r.right, r.bottom + 3.5f * d, shade)
        c.drawRect(r, yellow)
        val pitch = th * 1.25f
        val half = pitch * 0.5f
        val skew = th * 0.9f
        c.save()
        c.clipRect(r)
        var x = r.left - skew - t.phase * pitch
        while (x < r.right + skew) {
            stripePath.reset()
            stripePath.moveTo(x, r.bottom)
            stripePath.lineTo(x + half, r.bottom)
            stripePath.lineTo(x + half + skew, r.top)
            stripePath.lineTo(x + skew, r.top)
            stripePath.close()
            c.drawPath(stripePath, black)
            x += pitch
        }
        shine.shader = LinearGradient(
            0f, r.top, 0f, r.bottom,
            intArrayOf(0x33FFFFFF, 0x00FFFFFF, 0x33000000),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(r, shine)
        c.restore()
        c.drawLine(r.left, r.top, r.right, r.top, edge)
        c.drawLine(r.left, r.bottom, r.right, r.bottom, edge)
        c.restore()
    }
}
