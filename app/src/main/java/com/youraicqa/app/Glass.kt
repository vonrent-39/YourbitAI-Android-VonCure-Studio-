package com.youraicqa.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import java.util.WeakHashMap

object Glass {
    private val done = WeakHashMap<Drawable, Boolean>()

    fun clearRootBackground(root: View?) {
        if (root != null && root.background is ColorDrawable) root.background = null
    }

    fun restyle(root: View?) {
        if (root == null || Build.VERSION.SDK_INT < 24) return
        walk(root, 0)
    }

    private fun walk(v: View, depth: Int) {
        if (depth > 40) return
        tryRestyle(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
    }

    private fun tryRestyle(v: View) {
        val bg = v.background as? GradientDrawable ?: return
        if (done.containsKey(bg)) return
        val d = v.resources.displayMetrics.density
        if (v.height <= 0 || v.width <= 0) return
        done[bg] = true
        if (v.height < 34 * d || v.width < 60 * d) return
        if (bg.shape != GradientDrawable.RECTANGLE) return
        if (bg.colors != null) return
        val base = bg.color?.defaultColor ?: return
        if (Color.alpha(base) < 0xE0) return
        if (bg.cornerRadius < 10 * d) return
        val g = bg.mutate() as? GradientDrawable ?: return
        val top = ColorUtils.setAlphaComponent(
            ColorUtils.blendARGB(base, Color.WHITE, if (Th.light) 0.45f else 0.10f), 0xE6
        )
        val bottom = ColorUtils.setAlphaComponent(base, 0xBF)
        g.orientation = GradientDrawable.Orientation.TL_BR
        g.colors = intArrayOf(top, bottom)
        done[g] = true
    }
}
