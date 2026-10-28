package com.youraicqa.app

import android.content.Context
import android.view.View
import android.view.ViewGroup

class ChipFlowLayout(context: Context) : ViewGroup(context) {
    private class Row {
        val kids = ArrayList<View>()
        var w = 0
        var h = 0
    }

    var equalWidth: Boolean = false

    private val rows = ArrayList<Row>()
    private var contentWidth = 0

    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        if (p is MarginLayoutParams) MarginLayoutParams(p) else MarginLayoutParams(p ?: LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

    private fun outerW(c: View): Int { val lp = c.layoutParams as MarginLayoutParams; return c.measuredWidth + lp.leftMargin + lp.rightMargin }
    private fun outerH(c: View): Int { val lp = c.layoutParams as MarginLayoutParams; return c.measuredHeight + lp.topMargin + lp.bottomMargin }

    private fun greedy(kids: List<View>, avail: Int): List<Row> {
        val out = ArrayList<Row>()
        var cur = Row()
        for (c in kids) {
            val cw = outerW(c)
            if (cur.kids.isNotEmpty() && cur.w + cw > avail) { out.add(cur); cur = Row() }
            cur.kids.add(c); cur.w += cw; cur.h = maxOf(cur.h, outerH(c))
        }
        if (cur.kids.isNotEmpty()) out.add(cur)
        return out
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val avail = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val kids = ArrayList<View>()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val lp = c.layoutParams as MarginLayoutParams
            measureChild(c, MeasureSpec.makeMeasureSpec((avail - lp.leftMargin - lp.rightMargin).coerceAtLeast(0), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            kids.add(c)
        }
        if (equalWidth && kids.size > 1) {
            val target = kids.maxOf { outerW(it) }
            for (c in kids) {
                val lp = c.layoutParams as MarginLayoutParams
                val w = (target - lp.leftMargin - lp.rightMargin).coerceAtLeast(0)
                c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            }
            val th = kids.maxOf { it.measuredHeight }
            for (c in kids) c.measure(MeasureSpec.makeMeasureSpec(c.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY))
        }
        var laid = greedy(kids, avail)
        if (laid.size > 1) {
            val n = laid.size
            var lo = kids.maxOf { outerW(it) }
            var hi = avail
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (greedy(kids, mid).size == n) hi = mid else lo = mid + 1
            }
            laid = greedy(kids, hi)
        }
        rows.clear(); rows.addAll(laid)
        contentWidth = laid.maxOfOrNull { it.w } ?: 0
        val h = laid.sumOf { it.h } + paddingTop + paddingBottom
        val w = when (MeasureSpec.getMode(widthSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(widthSpec)
            else -> contentWidth + paddingLeft + paddingRight
        }
        setMeasuredDimension(w, resolveSize(h, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = r - l - paddingLeft - paddingRight
        var y = paddingTop
        for (row in rows) {
            var x = paddingLeft + ((avail - row.w) / 2).coerceAtLeast(0)
            for (c in row.kids) {
                val lp = c.layoutParams as MarginLayoutParams
                val cy = y + lp.topMargin + ((row.h - outerH(c)) / 2).coerceAtLeast(0)
                val cx = x + lp.leftMargin
                c.layout(cx, cy, cx + c.measuredWidth, cy + c.measuredHeight)
                x += outerW(c)
            }
            y += row.h
        }
    }
}
