package com.youraicqa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.util.ArrayDeque

class ImageEditorCanvasView(context: Context) : View(context) {

    enum class Mode { NONE, CROP, DRAW }

    var mode: Mode = Mode.NONE
        set(value) {
            field = value
            if (value == Mode.CROP && cropRect == null) resetCropRect()
            invalidate()
        }

    private var bitmap: Bitmap? = null
    private val undoStack = ArrayDeque<Bitmap>()
    private val maxUndo = 8

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private var cropRect: RectF? = null
    private val handleTouchRadiusPx = 44f
    private var draggingHandle = -1
    private var dragStart = PointF()
    private var cropRectAtDragStart: RectF? = null

    private val brushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var lastX = 0f
    private var lastY = 0f
    private var prevMidX = 0f
    private var prevMidY = 0f

    private val overlayPaint = Paint().apply { color = Color.argb(140, 0, 0, 0) }
    private val dens get() = resources.displayMetrics.density
    private val cropStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val glassShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glassRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()
    }
    private val glassGloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xB3FFFFFF.toInt()
    }
    private val glossRect = RectF()

    // Translucent glass bead for crop corners
    private fun drawGlassHandle(canvas: Canvas, x: Float, y: Float, r: Float) {
        val d = dens
        glassShadow.shader = android.graphics.RadialGradient(
            x, y + 2f * d, r + 5f * d,
            intArrayOf(0x66000000, 0x22000000, 0x00000000), floatArrayOf(0.55f, 0.8f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y + 2f * d, r + 5f * d, glassShadow)
        glassFill.shader = android.graphics.RadialGradient(
            x - r * 0.35f, y - r * 0.4f, r * 1.5f,
            intArrayOf(0x66FFFFFF, 0x1FFFFFFF, 0x0DFFFFFF), floatArrayOf(0f, 0.65f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, r, glassFill)
        glassRim.strokeWidth = 2f * d
        canvas.drawCircle(x, y, r, glassRim)
        val g = r * 0.62f
        glossRect.set(x - g, y - g, x + g, y + g)
        glassGloss.strokeWidth = maxOf(1.6f * d, r * 0.14f)
        canvas.drawArc(glossRect, 200f, 70f, false, glassGloss)
    }

    private fun ensureDrawable(src: Bitmap): Bitmap {
        if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) return src
        return src.copy(Bitmap.Config.ARGB_8888, true) ?: src
    }

    private fun safeRange(v: Float, lo: Float, hi: Float): Float {
        val h = if (hi < lo) lo else hi
        return if (v < lo) lo else if (v > h) h else v
    }

    fun setBitmap(bmp: Bitmap) {
        bitmap = ensureDrawable(bmp)
        undoStack.clear()
        cropRect = null
        requestLayout()
        invalidate()
    }

    fun currentBitmap(): Bitmap? = bitmap

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun undo() {
        val prev = undoStack.pollLast() ?: return
        bitmap = ensureDrawable(prev)
        recomputeTransform()
        cropRect = null
        invalidate()
    }

    private fun pushUndo() {
        val b = bitmap ?: return
        if (undoStack.size >= maxUndo) undoStack.pollFirst()
        try {
            val srcCfg: Bitmap.Config? = b.config
            val cfg: Bitmap.Config = if (srcCfg == null || srcCfg == Bitmap.Config.HARDWARE) Bitmap.Config.ARGB_8888 else srcCfg
            b.copy(cfg, true)?.let { undoStack.addLast(it) }
        } catch (_: OutOfMemoryError) {
            undoStack.clear()
        }
    }

    private fun resetCropRect() {
        val b = bitmap ?: return
        val inset = 0.12f
        cropRect = RectF(b.width * inset, b.height * inset, b.width * (1 - inset), b.height * (1 - inset))
    }

    fun applyCrop(): Boolean {
        val b = bitmap ?: return false
        val r = cropRect ?: return false
        val left = r.left.coerceIn(0f, b.width.toFloat())
        val top = r.top.coerceIn(0f, b.height.toFloat())
        val right = r.right.coerceIn(0f, b.width.toFloat())
        val bottom = r.bottom.coerceIn(0f, b.height.toFloat())
        val w = (right - left).toInt()
        val h = (bottom - top).toInt()
        if (w < 8 || h < 8) return false
        pushUndo()
        val cropped = try {
            Bitmap.createBitmap(b, left.toInt(), top.toInt(), w, h)
        } catch (_: Throwable) { return false }
        bitmap = ensureDrawable(cropped)
        cropRect = null
        mode = Mode.NONE
        recomputeTransform()
        requestLayout()
        invalidate()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeTransform()
    }

    private fun recomputeTransform() {
        val b = bitmap ?: return
        if (width <= 0 || height <= 0 || b.width <= 0 || b.height <= 0) return
        scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        offsetX = (width - b.width * scale) / 2f
        offsetY = (height - b.height * scale) / 2f
    }

    private fun viewToBitmapX(vx: Float): Float = (vx - offsetX) / scale
    private fun viewToBitmapY(vy: Float): Float = (vy - offsetY) / scale
    private fun bitmapToViewX(bx: Float): Float = bx * scale + offsetX
    private fun bitmapToViewY(by: Float): Float = by * scale + offsetY

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (scale == 1f && offsetX == 0f && offsetY == 0f) recomputeTransform()
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(b, 0f, 0f, null)
        canvas.restore()

        if (mode == Mode.CROP) {
            val r = cropRect ?: return
            val vLeft = bitmapToViewX(r.left)
            val vTop = bitmapToViewY(r.top)
            val vRight = bitmapToViewX(r.right)
            val vBottom = bitmapToViewY(r.bottom)
            canvas.drawRect(0f, 0f, width.toFloat(), vTop, overlayPaint)
            canvas.drawRect(0f, vBottom, width.toFloat(), height.toFloat(), overlayPaint)
            canvas.drawRect(0f, vTop, vLeft, vBottom, overlayPaint)
            canvas.drawRect(vRight, vTop, width.toFloat(), vBottom, overlayPaint)
            canvas.drawRect(vLeft, vTop, vRight, vBottom, cropStrokePaint)
            val tw = (vRight - vLeft) / 3f
            val th = (vBottom - vTop) / 3f
            for (k in 1..2) {
                canvas.drawLine(vLeft + tw * k, vTop, vLeft + tw * k, vBottom, gridPaint)
                canvas.drawLine(vLeft, vTop + th * k, vRight, vTop + th * k, gridPaint)
            }
            val hr = 11f * dens
            for ((hx, hy) in listOf(
                vLeft to vTop, vRight to vTop, vLeft to vBottom, vRight to vBottom
            )) drawGlassHandle(canvas, hx, hy, hr)
        }
    }

    private fun handleAt(vx: Float, vy: Float): Int {
        val r = cropRect ?: return -1
        val corners = listOf(
            bitmapToViewX(r.left) to bitmapToViewY(r.top),
            bitmapToViewX(r.right) to bitmapToViewY(r.top),
            bitmapToViewX(r.left) to bitmapToViewY(r.bottom),
            bitmapToViewX(r.right) to bitmapToViewY(r.bottom)
        )
        corners.forEachIndexed { i, (cx, cy) ->
            if ((vx - cx) * (vx - cx) + (vy - cy) * (vy - cy) <= handleTouchRadiusPx * handleTouchRadiusPx) return i
        }
        val vLeft = bitmapToViewX(r.left); val vTop = bitmapToViewY(r.top)
        val vRight = bitmapToViewX(r.right); val vBottom = bitmapToViewY(r.bottom)
        if (vx in vLeft..vRight && vy in vTop..vBottom) return 4
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return try {
            handleTouch(event)
        } catch (_: Throwable) {
            true
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (mode) {
            Mode.DRAW -> {
                val bx = viewToBitmapX(event.x)
                val by = viewToBitmapY(event.y)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        pushUndo()
                        lastX = bx; lastY = by
                        prevMidX = bx; prevMidY = by
                        val dot = Path().apply { moveTo(bx, by); lineTo(bx + 0.01f, by) }
                        drawSegment(dot)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val midX = (lastX + bx) / 2f
                        val midY = (lastY + by) / 2f
                        val segment = Path().apply {
                            moveTo(prevMidX, prevMidY)
                            quadTo(lastX, lastY, midX, midY)
                        }
                        drawSegment(segment)
                        prevMidX = midX; prevMidY = midY
                        lastX = bx; lastY = by
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val segment = Path().apply {
                            moveTo(prevMidX, prevMidY)
                            lineTo(lastX, lastY)
                        }
                        drawSegment(segment)
                    }
                }
                return true
            }
            Mode.CROP -> {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        draggingHandle = handleAt(event.x, event.y)
                        dragStart = PointF(event.x, event.y)
                        cropRectAtDragStart = cropRect?.let { RectF(it) }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val start = cropRectAtDragStart ?: return true
                        val dxBmp = (event.x - dragStart.x) / scale
                        val dyBmp = (event.y - dragStart.y) / scale
                        val r = RectF(start)
                        val bw = b.width.toFloat(); val bh = b.height.toFloat()
                        when (draggingHandle) {
                            0 -> { r.left = safeRange(start.left + dxBmp, 0f, r.right - 20f); r.top = safeRange(start.top + dyBmp, 0f, r.bottom - 20f) }
                            1 -> { r.right = safeRange(start.right + dxBmp, r.left + 20f, bw); r.top = safeRange(start.top + dyBmp, 0f, r.bottom - 20f) }
                            2 -> { r.left = safeRange(start.left + dxBmp, 0f, r.right - 20f); r.bottom = safeRange(start.bottom + dyBmp, r.top + 20f, bh) }
                            3 -> { r.right = safeRange(start.right + dxBmp, r.left + 20f, bw); r.bottom = safeRange(start.bottom + dyBmp, r.top + 20f, bh) }
                            4 -> {
                                val w = start.width(); val h = start.height()
                                var nl = start.left + dxBmp
                                var nt = start.top + dyBmp
                                nl = safeRange(nl, 0f, bw - w)
                                nt = safeRange(nt, 0f, bh - h)
                                r.set(nl, nt, nl + w, nt + h)
                            }
                        }
                        if (draggingHandle in 0..3) cropRect = r
                        else if (draggingHandle == 4) cropRect = r
                        invalidate()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        draggingHandle = -1
                        cropRectAtDragStart = null
                    }
                }
                return true
            }
            Mode.NONE -> return false
        }
    }

    private fun drawSegment(path: Path) {
        var b = bitmap ?: return
        if (!b.isMutable) { b = ensureDrawable(b); bitmap = b }
        val sc = if (scale > 0f) scale else 1f
        brushPaint.strokeWidth = (5f * resources.displayMetrics.density) / sc
        Canvas(b).drawPath(path, brushPaint)
        invalidate()
    }
}
