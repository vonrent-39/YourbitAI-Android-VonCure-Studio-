package com.youraicqa.app

import android.content.Context
import android.content.res.Configuration
import androidx.core.graphics.ColorUtils

object Th {
    @Volatile
    var light: Boolean = false

    private const val NEUTRAL_SAT = 0.12f

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun keepAlpha(src: Int, rgb: Int): Int = (rgb and 0x00FFFFFF) or (src and 0xFF000000.toInt())

    private fun hsl(c: Int): FloatArray {
        val out = FloatArray(3)
        ColorUtils.colorToHSL(c, out)
        return out
    }

    fun fg(c: Int): Int {
        if (!light) return c
        if ((c ushr 24) == 0) return c
        val p = hsl(c)
        val h = p[0]
        val s = p[1]
        val l = p[2]
        val rgb: Int = if (s < NEUTRAL_SAT) {
            if (l < 0.36f) return c
            ColorUtils.HSLToColor(floatArrayOf(0f, 0f, 0.12f + (1f - l) * 0.62f))
        } else {
            val cap = if (h in 45f..190f) 0.34f else 0.46f
            if (l <= cap) return c
            ColorUtils.HSLToColor(floatArrayOf(h, minOf(s, 0.58f), cap))
        }
        return keepAlpha(c, rgb)
    }

    fun bg(c: Int): Int {
        if (!light) return c
        val a = c ushr 24
        if (a == 0) return c
        val p = hsl(c)
        val h = p[0]
        val s = p[1]
        val l = p[2]
        val neutral = s < NEUTRAL_SAT || (s < 0.2f && h in 200f..290f && l < 0.2f)
        if (neutral) {
            if (a < 0xFF && l > 0.7f) return ((a * 0.55f).toInt() shl 24)
            if (l > 0.55f) return c
            if (l < 0.09f) return keepAlpha(c, 0xF7F8FA)
            val nl = when {
                l < 0.125f -> 1.0f
                else -> maxOf(0.62f, 1.0f - 0.9f * (l - 0.125f))
            }
            return keepAlpha(c, ColorUtils.HSLToColor(floatArrayOf(0f, 0f, nl)))
        }
        if (l >= 0.30f) return c
        val ns = minOf(0.75f, s * 1.6f + 0.05f)
        return keepAlpha(c, ColorUtils.HSLToColor(floatArrayOf(h, ns, 0.93f)))
    }

    fun line(c: Int): Int {
        if (!light) return c
        if ((c ushr 24) == 0) return c
        val p = hsl(c)
        val s = p[1]
        val l = p[2]
        if (s >= NEUTRAL_SAT) return fg(c)
        if (l >= 0.6f) return fg(c)
        val nl = (0.99f - 0.5f * l).coerceIn(0.6f, 0.94f)
        return keepAlpha(c, ColorUtils.HSLToColor(floatArrayOf(0f, 0f, nl)))
    }

    fun onAccent(c: Int): Int = c
}
