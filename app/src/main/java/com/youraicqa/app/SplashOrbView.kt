package com.youraicqa.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

/** Animated loading orb: glass disc with the logo, orbiting dots, a progress ring and success/error states. */
class SplashOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val accent = ContextCompat.getColor(context, R.color.app_accent)
    private val accent2 = ColorUtils.blendARGB(accent, if (Th.light) 0xFF1E88E5.toInt() else 0xFF6FE7C8.toInt(), 0.6f)
    private val okColor = 0xFF3FBF6F.toInt()
    private val badColor = 0xFFE5534B.toInt()
    private val logo = ContextCompat.getDrawable(context, R.drawable.ic_ai_logo)?.mutate()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private val checkPath = Path()
    private val checkSeg = Path()

    private var phase = 0f
    private var shownProgress = 0f
    private var successT = 0f
    private var errorMix = 0f
    private var animated = true

    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 9000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedFraction; invalidate() }
    }
    private var progressAnim: ValueAnimator? = null

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun alpha(color: Int, a: Float) = ColorUtils.setAlphaComponent(color, a.toInt().coerceIn(0, 255))

    fun setAnimated(on: Boolean) {
        animated = on
        if (!on) { loop.cancel(); phase = 0f; invalidate() } else if (isAttachedToWindow && !loop.isStarted) loop.start()
    }

    fun setProgress(p: Float) {
        progressAnim?.cancel()
        progressAnim = ValueAnimator.ofFloat(shownProgress, p.coerceIn(0f, 1f)).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener { shownProgress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun playSuccess(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(successT, 1f).apply {
            duration = 750
            interpolator = DecelerateInterpolator()
            addUpdateListener { successT = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { onEnd() }
            })
            start()
        }
    }

    fun playFail() {
        ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -dp(10f), dp(10f), -dp(6f), dp(6f), 0f).setDuration(420).start()
        ValueAnimator.ofFloat(0f, 1f, 1f, 0f).apply {
            duration = 1400
            addUpdateListener { errorMix = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) loop.start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        progressAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        val tau = 6.2831855f
        val pulse = 0.5f + 0.5f * sin(phase * tau * 3f)
        val live = 1f - successT
        val tone = ColorUtils.blendARGB(ColorUtils.blendARGB(accent, badColor, errorMix), okColor, successT)

        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(cx, cy, r, intArrayOf(alpha(tone, 80f + 50f * pulse), alpha(tone, 0f)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, paint)

        val stroke = dp(3f)
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        val ringR = r * 0.88f - stroke
        rect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        paint.color = alpha(tone, 40f)
        c.drawArc(rect, 0f, 360f, false, paint)
        val sweep = shownProgress * 360f
        if (sweep > 1f) {
            val a1 = ColorUtils.blendARGB(ColorUtils.blendARGB(accent, badColor, errorMix), okColor, successT)
            val a2 = ColorUtils.blendARGB(accent2, okColor, successT)
            val g = SweepGradient(cx, cy, intArrayOf(a1, a2, a1), null)
            matrix.reset(); matrix.postRotate(-90f, cx, cy); g.setLocalMatrix(matrix)
            paint.shader = g
            c.drawArc(rect, -90f, sweep, false, paint)
            paint.shader = null
            val ha = Math.toRadians((-90f + sweep).toDouble())
            paint.style = Paint.Style.FILL
            paint.color = alpha(0xFFFFFFFF.toInt(), 230f)
            c.drawCircle(cx + ringR * cos(ha).toFloat(), cy + ringR * sin(ha).toFloat(), stroke * 0.9f, paint)
        }

        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(2f)
        fun arcs(radius: Float, count: Int, span: Float, rot: Float, a: Float, color: Int) {
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.color = alpha(color, a * live)
            for (i in 0 until count) c.drawArc(rect, rot + i * 360f / count, span, false, paint)
        }
        arcs(r * 0.74f, 3, 64f, phase * 360f * 2f, 150f, accent)
        arcs(r * 0.64f, 2, 90f, -phase * 360f * 3f, 110f, accent2)

        val tilt = Math.toRadians(-24.0)
        val ct = cos(tilt).toFloat()
        val st = sin(tilt).toFloat()
        fun dots(front: Boolean) {
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            for (k in 0 until 5) {
                val col = if (k % 2 == 0) accent else accent2
                for (j in 0..5) {
                    val a = (phase * 3f + k / 5f) * tau - j * 0.09f
                    val isFront = sin(a) > 0f
                    if (isFront != front) continue
                    val ex = cos(a) * r * 0.80f
                    val ey = sin(a) * r * 0.30f
                    val x = cx + ex * ct - ey * st
                    val y = cy + ex * st + ey * ct
                    val fade = (1f - j / 6f) * live * (if (front) 1f else 0.55f)
                    paint.color = alpha(col, 255f * fade)
                    c.drawCircle(x, y, dp(if (front) 3.4f else 2.5f) * (1f - j * 0.11f), paint)
                }
            }
        }
        dots(false)

        val discR = r * 0.44f
        paint.reset(); paint.isAntiAlias = true
        val top = if (Th.light) 0xF2FFFFFF.toInt() else 0x38FFFFFF
        val bottom = if (Th.light) alpha(accent, 40f) else 0x0AFFFFFF
        paint.shader = LinearGradient(cx - discR, cy - discR, cx + discR, cy + discR, top, bottom, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, discR, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = ColorUtils.blendARGB(if (Th.light) alpha(accent, 110f) else 0x59FFFFFF, okColor, successT)
        c.drawCircle(cx, cy, discR, paint)
        rect.set(cx - discR * 0.82f, cy - discR * 0.82f, cx + discR * 0.82f, cy + discR * 0.82f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(2f)
        paint.color = alpha(0xFFFFFFFF.toInt(), if (Th.light) 255f else 120f)
        c.drawArc(rect, 200f, 46f, false, paint)

        if (live > 0.01f && logo != null) {
            val half = (r * 0.23f * (1f + 0.035f * sin(phase * tau * 3f))).toInt()
            logo.setTint(tone)
            logo.alpha = (255 * live).toInt()
            logo.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            logo.draw(c)
        }

        dots(true)

        if (successT > 0f) {
            val ripple = r * (0.5f + 0.45f * successT)
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = alpha(okColor, 140f * (1f - successT))
            c.drawCircle(cx, cy, ripple, paint)

            val trim = ((successT - 0.3f) / 0.7f).coerceIn(0f, 1f)
            if (trim > 0f) {
                checkPath.reset()
                checkPath.moveTo(cx - r * 0.17f, cy + r * 0.01f)
                checkPath.lineTo(cx - r * 0.05f, cy + r * 0.13f)
                checkPath.lineTo(cx + r * 0.19f, cy - r * 0.12f)
                val pm = PathMeasure(checkPath, false)
                checkSeg.reset()
                pm.getSegment(0f, pm.length * trim, checkSeg, true)
                paint.strokeWidth = dp(5f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = okColor
                c.drawPath(checkSeg, paint)
            }
        }
    }
}
