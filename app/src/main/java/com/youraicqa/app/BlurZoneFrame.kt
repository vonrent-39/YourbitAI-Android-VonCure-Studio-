package com.youraicqa.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

class BlurZoneFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var overlayView: View? = null
        set(value) { field = value; invalidate() }

    var blurTargets: List<View> = emptyList()
        set(value) { field = value; invalidate() }

    private var enabledBlur = false
    private var radius = 0f
    private var skipOverlay = false

    private val clipPath = Path()
    private val tmpRect = Rect()
    private val tmpRectF = RectF()
    private val node: RenderNode? = if (Build.VERSION.SDK_INT >= 31) RenderNode("chatBlurCapsules") else null

    fun setZone(top: Int, blurRadiusPx: Float) {
        val on = top >= 0 && blurRadiusPx > 0f
        if (on == enabledBlur && blurRadiusPx == radius) return
        enabledBlur = on
        radius = blurRadiusPx
        invalidate()
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (skipOverlay && child === overlayView) return false
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val overlay = overlayView
        if (overlay == null || !enabledBlur || radius <= 0f || !canvas.isHardwareAccelerated ||
            Build.VERSION.SDK_INT < 31 || blurTargets.isEmpty()
        ) {
            super.dispatchDraw(canvas)
            return
        }
        clipPath.reset()
        var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
        for (v in blurTargets) {
            if (!v.isShown || v.width <= 0 || v.height <= 0) continue
            tmpRect.set(0, 0, v.width, v.height)
            try { offsetDescendantRectToMyCoords(v, tmpRect) } catch (_: Exception) { continue }
            tmpRectF.set(tmpRect)
            val r = minOf(tmpRectF.width(), tmpRectF.height()) / 2f
            clipPath.addRoundRect(tmpRectF, r, r, Path.Direction.CW)
            left = minOf(left, tmpRect.left); top = minOf(top, tmpRect.top)
            right = maxOf(right, tmpRect.right); bottom = maxOf(bottom, tmpRect.bottom)
        }
        if (right <= left || bottom <= top) {
            super.dispatchDraw(canvas)
            return
        }
        drawWithBlur(canvas, overlay, left, top, right, bottom, drawingTime)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawWithBlur(canvas: Canvas, overlay: View, l: Int, t: Int, r: Int, b: Int, drawingTime: Long) {
        val node = node ?: run { super.dispatchDraw(canvas); return }
        skipOverlay = true
        try { super.dispatchDraw(canvas) } finally { skipOverlay = false }

        val margin = (radius * 2).toInt()
        val nl = (l - margin).coerceAtLeast(0)
        val nt = (t - margin).coerceAtLeast(0)
        val nr = (r + margin).coerceAtMost(width)
        val nb = (b + margin).coerceAtMost(height)
        val nw = nr - nl
        val nh = nb - nt
        if (nw <= 0 || nh <= 0) { drawChild(canvas, overlay, drawingTime); return }
        node.setPosition(0, 0, nw, nh)
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        val rc = node.beginRecording(nw, nh)
        try {
            rc.translate(-nl.toFloat(), -nt.toFloat())
            skipOverlay = true
            try { super.dispatchDraw(rc) } finally { skipOverlay = false }
        } finally {
            node.endRecording()
        }

        canvas.save()
        canvas.clipPath(clipPath)
        canvas.translate(nl.toFloat(), nt.toFloat())
        canvas.drawRenderNode(node)
        canvas.restore()

        drawChild(canvas, overlay, drawingTime)
    }
}
