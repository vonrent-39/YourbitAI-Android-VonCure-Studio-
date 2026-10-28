package com.youraicqa.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class ColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    private var hue = 0f
    private var sat = 0f
    private var value = 1f

    private var cx = 0f
    private var cy = 0f
    private var outerRadius = 0f
    private var ringWidth = 0f
    private var innerRadius = 0f
    private var boxLeft = 0f
    private var boxTop = 0f
    private var boxSide = 0f

    private val BOX_ROTATE_DEG = 45f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val boxBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxSatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boxValPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()
    }
    private val glassInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x40000000
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xB3FFFFFF.toInt()
    }
    private val glossRect = android.graphics.RectF()

    private var dragMode = DragMode.NONE
    private enum class DragMode { NONE, RING, BOX }

    private var colorAnimator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        outerRadius = min(w, h) / 2f - dp(4f)
        ringWidth = outerRadius * 0.20f
        innerRadius = outerRadius - ringWidth - dp(4f)
        boxSide = innerRadius * sqrt(2f)
        boxLeft = cx - boxSide / 2f
        boxTop = cy - boxSide / 2f

        val hueColors = IntArray(13)
        for (i in 0..12) hueColors[i] = Color.HSVToColor(floatArrayOf(i * 30f, 1f, 1f))
        ringPaint.strokeWidth = ringWidth
        ringPaint.shader = SweepGradient(cx, cy, hueColors, null)

        boxValPaint.shader = LinearGradient(
            0f, boxTop, 0f, boxTop + boxSide,
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP
        )
    }

    private fun rebuildBoxShaders() {
        val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        boxBasePaint.color = hueColor
        boxSatPaint.shader = LinearGradient(
            boxLeft, 0f, boxLeft + boxSide, 0f,
            0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (boxSide <= 0f) return
        rebuildBoxShaders()

        canvas.drawCircle(cx, cy, innerRadius + ringWidth / 2f, ringPaint)

        canvas.save()
        canvas.rotate(BOX_ROTATE_DEG, cx, cy)
        canvas.clipRect(boxLeft, boxTop, boxLeft + boxSide, boxTop + boxSide)
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxSide, boxTop + boxSide, boxBasePaint)
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxSide, boxTop + boxSide, boxSatPaint)
        canvas.drawRect(boxLeft, boxTop, boxLeft + boxSide, boxTop + boxSide, boxValPaint)
        canvas.restore()

        val hueRad = Math.toRadians(hue.toDouble())
        val ringHandleR = innerRadius + ringWidth / 2f
        val rx = cx + (cos(hueRad) * ringHandleR).toFloat()
        val ry = cy + (sin(hueRad) * ringHandleR).toFloat()
        drawHandle(canvas, rx, ry, ringWidth / 2f * 0.62f)

        val localBx = boxLeft + sat * boxSide
        val localBy = boxTop + (1f - value) * boxSide
        val (bx, by) = rotateAroundCenter(localBx, localBy, BOX_ROTATE_DEG)
        drawHandle(canvas, bx, by, dp(9f))
    }

    private fun rotateAroundCenter(x: Float, y: Float, angleDeg: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA
        return (cx + rx) to (cy + ry)
    }

    // Translucent glass bead: the picked color shows through, with a rim and a specular arc.
    private fun drawHandle(canvas: Canvas, x: Float, y: Float, r: Float) {
        handleShadowPaint.shader = RadialGradient(
            x, y + dp(2f), r + dp(5f),
            intArrayOf(0x66000000, 0x22000000, 0x00000000), floatArrayOf(0.55f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y + dp(2f), r + dp(5f), handleShadowPaint)

        glassFillPaint.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.4f, r * 1.5f,
            intArrayOf(0x66FFFFFF, 0x1FFFFFFF, 0x0DFFFFFF), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, r, glassFillPaint)

        glassInnerPaint.strokeWidth = dp(1f)
        canvas.drawCircle(x, y, r - dp(1.5f), glassInnerPaint)
        glassRimPaint.strokeWidth = dp(2f)
        canvas.drawCircle(x, y, r, glassRimPaint)

        val g = r * 0.62f
        glossRect.set(x - g, y - g, x + g, y + g)
        glossPaint.strokeWidth = maxOf(dp(1.6f), r * 0.14f)
        canvas.drawArc(glossRect, 200f, 70f, false, glossPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val (lx, ly) = rotateAroundCenter(x, y, -BOX_ROTATE_DEG)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = if (lx in boxLeft..(boxLeft + boxSide) && ly in boxTop..(boxTop + boxSide)) {
                    DragMode.BOX
                } else {
                    DragMode.RING
                }
                applyTouch(x, y, lx, ly)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> applyTouch(x, y, lx, ly)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun applyTouch(x: Float, y: Float, lx: Float, ly: Float) {
        when (dragMode) {
            DragMode.BOX -> {
                val cxr = lx.coerceIn(boxLeft, boxLeft + boxSide)
                val cyr = ly.coerceIn(boxTop, boxTop + boxSide)
                sat = ((cxr - boxLeft) / boxSide).coerceIn(0f, 1f)
                value = (1f - (cyr - boxTop) / boxSide).coerceIn(0f, 1f)
            }
            DragMode.RING -> {
                val angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
                hue = (if (angle < 0) angle + 360 else angle).toFloat()
            }
            DragMode.NONE -> return
        }
        invalidate()
        onColorChanged?.invoke(currentColor())
    }

    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(hue, sat, value))

    fun setColorSilently(color: Int, animate: Boolean = true) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val fromHue = hue
        val fromSat = sat
        val fromValue = value
        val toHueRaw = hsv[0]
        val toSat = hsv[1]
        val toValue = hsv[2]

        colorAnimator?.cancel()

        if (!animate || width == 0) {
            hue = toHueRaw
            sat = toSat
            value = toValue
            invalidate()
            return
        }

        var delta = toHueRaw - fromHue
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        val toHue = fromHue + delta

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                hue = (fromHue + (toHue - fromHue) * t + 360f) % 360f
                sat = fromSat + (toSat - fromSat) * t
                value = fromValue + (toValue - fromValue) * t
                invalidate()
            }
            start()
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
