package com.youraicqa.app

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.widget.ImageView
import android.widget.TextView

object Ico {
    fun drawable(ctx: Context, res: Int, sizeDp: Float, tint: Int?): Drawable? {
        val d = ActionIcons.drawable(ctx, res)?.mutate() ?: return null
        val px = (sizeDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        d.setBounds(0, 0, px, px)
        if (tint != null) d.setTint(tint)
        return d
    }

    fun imageView(ctx: Context, res: Int, tint: Int?): ImageView = ImageView(ctx).apply {
        ActionIcons.apply(this, res)
        if (tint != null) setColorFilter(tint)
    }

    fun start(tv: TextView, res: Int, sizeDp: Float, tint: Int?, paddingDp: Float = 6f) {
        val d = drawable(tv.context, res, sizeDp, tint)
        tv.setCompoundDrawablesRelative(d, null, null, null)
        tv.compoundDrawablePadding = (paddingDp * tv.resources.displayMetrics.density).toInt()
    }

    fun prefixed(ctx: Context, res: Int, sizeDp: Float, tint: Int?, text: CharSequence): CharSequence {
        val d = drawable(ctx, res, sizeDp, tint) ?: return text
        val s = SpannableString("   $text")
        s.setSpan(ImageSpan(d, ImageSpan.ALIGN_BOTTOM), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    fun glyph(ctx: Context, res: Int, sizeDp: Float, tint: Int?): CharSequence {
        val d = drawable(ctx, res, sizeDp, tint) ?: return ""
        val s = SpannableString("\u200B")
        s.setSpan(CenterImageSpan(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    fun glyphWithText(ctx: Context, res: Int, sizeDp: Float, tint: Int?, text: CharSequence): CharSequence {
        val d = drawable(ctx, res, sizeDp, tint) ?: return text
        val s = SpannableString("\u200B  $text")
        s.setSpan(CenterImageSpan(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }
}

class CenterImageSpan(d: Drawable) : ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM) {
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val b = drawable
        canvas.save()
        val dy = top + (bottom - top - b.bounds.height()) / 2f
        canvas.translate(x, dy)
        b.draw(canvas)
        canvas.restore()
    }
}
