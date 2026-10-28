package com.youraicqa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.core.content.ContextCompat

object ActionIcons {
    private val cache = HashMap<Int, Bitmap>()

    fun drawable(ctx: Context, res: Int): Drawable? {
        val src = ContextCompat.getDrawable(ctx, res) ?: return null
        if (src !is BitmapDrawable) return src
        val bmp = synchronized(cache) { cache.getOrPut(res) { normalize(src.bitmap) } }
        return BitmapDrawable(ctx.resources, bmp)
    }

    fun apply(iv: ImageView, res: Int) {
        val d = drawable(iv.context, res)
        if (d != null) iv.setImageDrawable(d) else iv.setImageResource(res)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.adjustViewBounds = false
    }

    private fun normalize(b: Bitmap): Bitmap {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 24) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return b
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        val side = maxOf(cw, ch)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(b, -minX.toFloat() + (side - cw) / 2f, -minY.toFloat() + (side - ch) / 2f, null)
        return out
    }
}
