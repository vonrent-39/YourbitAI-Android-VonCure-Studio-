package com.youraicqa.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.LinearLayout

class TableCardLayout(
    context: Context,
    private val radiusPx: Float,
    strokePx: Float,
    strokeColor: Int
) : LinearLayout(context) {

    private val clip = Path()
    private val box = RectF()
    private val strokeBox = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = strokeColor
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        box.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(box, radiusPx, radiusPx, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)

        val half = stroke.strokeWidth / 2f
        strokeBox.set(half, half, w - half, h - half)
        canvas.drawRoundRect(strokeBox, radiusPx - half, radiusPx - half, stroke)
    }
}
