package com.youraicqa.app

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import kotlin.math.cos
import kotlin.math.PI

enum class PressAnimStyle { BOUNCE, SOFT, LIFT, POP }

// Settles at 1 with damped wobbles; `wobbles` is the number of half-cycles
private class SpringBelowOne(private val wobbles: Float) : Interpolator {
    override fun getInterpolation(t: Float): Float {
        val k = 1f - t
        val g = k * k * (1f + cos(wobbles * PI.toFloat() * t)) / 2f
        return (1f - g).coerceIn(0f, 1f)
    }
}
private val SPRING_BOUNCE = SpringBelowOne(3f)
private val SPRING_POP = SpringBelowOne(2f)

// Scaled views get cut off by clipping parents
private fun View.disableAncestorClippingForAnim() {
    var p = this.parent
    var levels = 0
    while (p is ViewGroup && levels < 2) {
        p.clipChildren = false
        p.clipToPadding = false
        p = p.parent
        levels++
    }
}

fun View.applyPressAnimation(style: PressAnimStyle = PressAnimStyle.BOUNCE) {
    installPressRipple()
    installPressResetOnDetach()
    setOnTouchListener { v, event ->
        if (!EconomyPrefs.pressAnimationsEnabled(v.context)) return@setOnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressIn(v, style)
            MotionEvent.ACTION_MOVE -> {
                val out = event.x < 0f || event.y < 0f || event.x > v.width || event.y > v.height
                if (out && v.getTag(R.id.tagPressDown) == true) pressOut(v, style)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (v.getTag(R.id.tagPressDown) == true) pressOut(v, style)
            }
        }
        false
    }
}

// Rest alpha is kept from the first press until the release animation finishes, so rapid taps never reuse a half-faded value.
private fun pressIn(v: View, style: PressAnimStyle) {
    // An entrance fade (stagger) may be running: cancelling it below would freeze the view half-visible
    if (v.getTag(R.id.tagEntrancePending) == true) {
        v.setTag(R.id.tagEntrancePending, null)
        v.animate().cancel()
        v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
        v.setTag(R.id.tagPressBaseAlpha, null)
    }
    if (v.getTag(R.id.tagPressBaseAlpha) == null) v.setTag(R.id.tagPressBaseAlpha, v.alpha)
    v.setTag(R.id.tagPressDown, true)
    val a = v.animate()
    a.cancel()
    a.setListener(null)
    a.setInterpolator(DecelerateInterpolator()).setStartDelay(0)
    when (style) {
        PressAnimStyle.BOUNCE -> a.scaleX(0.90f).scaleY(0.90f).setDuration(90)
        PressAnimStyle.SOFT -> a.alpha(0.55f).setDuration(80)
        PressAnimStyle.LIFT -> a.scaleX(0.965f).scaleY(0.965f).alpha(0.86f).setDuration(110)
        PressAnimStyle.POP -> a.scaleX(0.86f).scaleY(0.86f).alpha(0.75f).setDuration(85)
    }
    a.start()
}

private fun pressOut(v: View, style: PressAnimStyle) {
    val base = (v.getTag(R.id.tagPressBaseAlpha) as? Float) ?: v.alpha
    v.setTag(R.id.tagPressDown, null)
    val a = v.animate()
    a.cancel()
    a.setListener(null)
    a.setStartDelay(0)
    when (style) {
        PressAnimStyle.BOUNCE -> a.scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(SPRING_BOUNCE)
        PressAnimStyle.SOFT -> a.alpha(base).setDuration(150).setInterpolator(DecelerateInterpolator())
        PressAnimStyle.LIFT -> a.scaleX(1f).scaleY(1f).alpha(base).setDuration(160).setInterpolator(DecelerateInterpolator())
        PressAnimStyle.POP -> a.scaleX(1f).scaleY(1f).alpha(base).setDuration(200).setInterpolator(SPRING_POP)
    }
    // Pin the final state: runs only when the release animation completes (a new press cancels it and reuses the same rest alpha)
    a.withEndAction {
        v.scaleX = 1f; v.scaleY = 1f; v.alpha = base
        if (v.getTag(R.id.tagPressDown) != true) v.setTag(R.id.tagPressBaseAlpha, null)
    }
    a.start()
}

// A view detached mid-press would otherwise stay scaled or dimmed
private fun resetPressState(v: View) {
    val base = v.getTag(R.id.tagPressBaseAlpha) as? Float ?: return
    v.animate().cancel()
    v.scaleX = 1f; v.scaleY = 1f
    v.alpha = base
    v.setTag(R.id.tagPressBaseAlpha, null)
    v.setTag(R.id.tagPressDown, null)
}

private fun View.installPressResetOnDetach() {
    if (getTag(R.id.tagPressInstalled) == true) return
    setTag(R.id.tagPressInstalled, true)
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { resetPressState(v) }
        override fun onViewDetachedFromWindow(v: View) { resetPressState(v) }
    })
}

private class PressMask(private val owner: View) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private fun radius(): Float {
        val w = owner.width.toFloat(); val h = owner.height.toFloat()
        val half = minOf(w, h) / 2f
        val d = owner.resources.displayMetrics.density
        val bg = owner.background
        val gd = (bg as? GradientDrawable) ?: ((bg as? LayerDrawable)?.takeIf { it.numberOfLayers > 0 }?.getDrawable(0) as? GradientDrawable)
        if (gd != null) {
            if (gd.shape == GradientDrawable.OVAL) return half
            val r = gd.cornerRadius
            if (r > 0f) return minOf(r, half)
            return 0f
        }
        return if (bg == null && w <= 64 * d && h <= 64 * d && kotlin.math.abs(w - h) < 6 * d) half else minOf(12f * d, half)
    }
    override fun draw(canvas: Canvas) {
        val r = radius()
        canvas.drawRoundRect(RectF(bounds), r, r, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

private fun View.installPressRipple() {
    if (Build.VERSION.SDK_INT < 23) return
    if (!AdvancedPrefs.pressHighlight(context)) return
    val existing = foreground
    if (existing != null && existing !is RippleDrawable) return
    if (existing is RippleDrawable && getTag(R.id.tagPressInstalled) == true) return
    val color = if (Th.light) 0x406C4FCE else 0x40FFFFFF
    foreground = RippleDrawable(ColorStateList.valueOf(color), null, PressMask(this))
}

fun View.playReactionAnim(tilt: Float) {
    if (!EconomyPrefs.pressAnimationsEnabled(context)) return
    animate().cancel()
    alpha = 1f
    getTag(R.id.tagReactionAnim)?.let { (it as? ValueAnimator)?.cancel() }
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 520
        addUpdateListener { a ->
            val f = a.animatedFraction
            val dipEnd = 0.28f
            val scale = if (f < dipEnd) {
                1f - 0.42f * (f / dipEnd)
            } else {
                val g = (f - dipEnd) / (1f - dipEnd)
                val k = 1f - g
                (1f - 0.42f * k * k * (1f + cos(3f * PI.toFloat() * g)) / 2f).coerceIn(0.5f, 1f)
            }
            scaleX = scale
            scaleY = scale
            val k2 = 1f - f
            rotation = tilt * k2 * k2 * kotlin.math.sin(3f * PI.toFloat() * f)
        }
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                scaleX = 1f; scaleY = 1f; rotation = 0f; alpha = 1f
            }
            override fun onAnimationCancel(animation: android.animation.Animator) {
                scaleX = 1f; scaleY = 1f; rotation = 0f
            }
        })
    }
    setTag(R.id.tagReactionAnim, anim)
    anim.start()
}

// Fades children in one after another; the end action pins the final state if the animation is cancelled
fun ViewGroup.staggerChildrenIn(baseDelayMs: Long = 30L, stepMs: Long = 45L, limit: Int = 14, dy: Float = 18f) {
    val n = minOf(childCount, limit)
    for (i in 0 until n) {
        val v = getChildAt(i)
        if (v.visibility != View.VISIBLE) continue
        v.animate().cancel()
        v.alpha = 0f
        v.translationY = dy * resources.displayMetrics.density / 2f
        v.scaleX = 0.985f; v.scaleY = 0.985f
        v.setTag(R.id.tagEntrancePending, true)
        v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(baseDelayMs + stepMs * i).setDuration(300)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction { v.alpha = 1f; v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.setTag(R.id.tagEntrancePending, null) }
            .start()
    }
}

/** Entrance fade for a single view (used outside of stagger helpers) that is safe against taps and cancellation. */
fun View.fadeSlideIn(delayMs: Long, dyPx: Float, durationMs: Long = 280L) {
    animate().cancel()
    alpha = 0f; translationY = dyPx
    setTag(R.id.tagEntrancePending, true)
    animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(durationMs)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction { alpha = 1f; translationY = 0f; setTag(R.id.tagEntrancePending, null) }
        .start()
}

/** Expands/collapses text with a fade: restarting it quickly can never leave the view half transparent. */
fun View.fadeInSafely(durationMs: Long = 220L) {
    animate().cancel()
    alpha = 0f
    animate().alpha(1f).setDuration(durationMs).withEndAction { alpha = 1f }.start()
}

fun android.app.Activity.staggerScreenContent() {
    fun findScroll(v: View?): android.widget.ScrollView? {
        if (v is android.widget.ScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }
    val sv = findScroll(window?.decorView) ?: return
    (sv.getChildAt(0) as? ViewGroup)?.staggerChildrenIn()
}
