package com.youraicqa.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.util.AttributeSet
import android.view.View
import java.io.File

class AnimatedGifView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var movieStart: Long = 0L
    var loadError: Boolean = false
        private set

    fun loadFromFile(path: String) {
        loadError = false
        movie = try {
            File(path).takeIf { it.exists() && it.length() > 0 }
                ?.let { Movie.decodeFile(it.absolutePath) }
        } catch (_: Exception) { null }
        if (movie == null || movie!!.width() <= 0 || movie!!.height() <= 0) {
            loadError = true
            movie = null
        }
        movieStart = 0L
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val m = movie
        if (m == null) {
            setMeasuredDimension(
                resolveSize(suggestedMinimumWidth, widthMeasureSpec),
                resolveSize(suggestedMinimumHeight, heightMeasureSpec)
            )
            return
        }
        val wSpecSize = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val wSpecMode = android.view.View.MeasureSpec.getMode(widthMeasureSpec)
        val targetW = if (wSpecMode == android.view.View.MeasureSpec.UNSPECIFIED) m.width() else wSpecSize
        val scale = if (m.width() > 0) targetW.toFloat() / m.width() else 1f
        val targetH = (m.height() * scale).toInt().coerceAtLeast(1)
        setMeasuredDimension(targetW.coerceAtLeast(1), targetH)
    }

    override fun onDraw(canvas: Canvas) {
        val m = movie ?: return
        if (movieStart == 0L) movieStart = android.os.SystemClock.uptimeMillis()
        val duration = m.duration().takeIf { it > 0 } ?: 1000
        val elapsed = (android.os.SystemClock.uptimeMillis() - movieStart) % duration
        m.setTime(elapsed.toInt())
        val scale = if (m.width() > 0) width.toFloat() / m.width() else 1f
        canvas.save()
        canvas.scale(scale, scale)
        m.draw(canvas, 0f, 0f)
        canvas.restore()
        invalidate()
    }

    companion object {
        fun canPlay(path: String?): Boolean {
            if (path.isNullOrBlank()) return false
            return try { File(path).exists() } catch (_: Exception) { false }
        }
    }
}
