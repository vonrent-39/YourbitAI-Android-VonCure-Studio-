package com.youraicqa.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class StatsActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true

    private var periodDays = 7
    private var chartMode = 0
    private lateinit var body: LinearLayout
    private val tabs = HashMap<Int, TextView>()

    private fun t(ru: String, en: String) = Loc.pick(ru, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)
        chartMode = try { getSharedPreferences("nanoai", Context.MODE_PRIVATE).getInt("stats_chart_mode", 0).coerceIn(0, 2) } catch (_: Exception) { 0 }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF0B0B0E.toInt()))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(12), dp(12), dp(4))
        }
        header.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt())); textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_stats)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(8) }
        })
        header.addView(TextView(this).apply {
            text = t("Статистика", "Statistics"); textSize = 20f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(6), dp(18), dp(24)) }
        scroll.addView(content); root.addView(scroll)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Th.bg(0xFF17171D.toInt())); cornerRadius = dp(14).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) }
        }
        for ((d, label) in listOf(1 to t("Сегодня", "Today"), 7 to t("7 дней", "7 days"), 30 to t("30 дней", "30 days"), 0 to t("Всё время", "All time"))) {
            val tv = TextView(this).apply {
                text = label; textSize = 12.5f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(10)); maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener { periodDays = d; refreshTabs(); render() }
            }
            tabs[d] = tv; tabRow.addView(tv)
        }
        content.addView(tabRow)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body)

        setContentView(root)
        refreshTabs(); render()
    }

    override fun onResume() { super.onResume(); if (::body.isInitialized) render() }

    private fun refreshTabs() {
        for ((d, tv) in tabs) {
            val sel = d == periodDays
            tv.setTextColor(Th.fg(if (sel) 0xFFFFFFFF.toInt() else 0xFF8A8A92.toInt()))
            tv.background = if (sel) GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Th.bg(0xFF34343C.toInt())) } else null
        }
    }

    private fun fmt(n: Long): String = when {
        n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(java.util.Locale.US, "%.1fK", n / 1000.0)
        else -> n.toString()
    }

    private fun statCard(title: String, value: String, color: Int, weightParams: Boolean = true): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Th.bg(0xFF16161C.toInt())); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(4); marginStart = dp(4) }
            addView(TextView(this@StatsActivity).apply {
                text = value; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(color))
            })
            addView(TextView(this@StatsActivity).apply {
                text = title; textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, dp(2), 0, 0); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }

    private fun pair(a: View, b: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        addView(a); addView(b)
    }

    private fun section(title: String) = TextView(this).apply {
        text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(dp(4), dp(12), 0, dp(8))
    }

    private fun render() {
        body.removeAllViews()
        val snap = StatsPrefs.snapshot(this, if (periodDays == 0) 30 else periodDays)
        val m = if (periodDays == 0) snap.total else snap.period
        fun g(k: String) = m[k] ?: 0L

        val tokens = g(StatsPrefs.TOK_IN) + g(StatsPrefs.TOK_OUT)
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1A2E.toInt())); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Th.line(0xFF4B3F7A.toInt())) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
            addView(TextView(this@StatsActivity).apply {
                text = "≈ " + fmt(tokens); textSize = 30f; setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFB39DFF.toInt()))
            })
            addView(TextView(this@StatsActivity).apply {
                text = t("потрачено токенов (оценка)", "tokens spent (estimate)"); textSize = 13f; setTextColor(Th.fg(0xFFCCCCD6.toInt()))
            })
            addView(TextView(this@StatsActivity).apply {
                text = t("в запросах ", "in requests ") + fmt(g(StatsPrefs.TOK_IN)) + "  ·  " + t("в ответах ", "in replies ") + fmt(g(StatsPrefs.TOK_OUT))
                textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, dp(4), 0, 0)
            })
        })

        body.addView(pair(
            statCard(t("сообщений отправлено", "messages sent"), fmt(g(StatsPrefs.MSG_SENT)), 0xFF64B5F6.toInt()),
            statCard(t("ответов получено", "replies received"), fmt(g(StatsPrefs.REPLIES)), 0xFF81C784.toInt())
        ))
        body.addView(pair(
            statCard(t("ошибок", "errors"), fmt(g(StatsPrefs.ERRORS)), 0xFFFF6B6B.toInt()),
            statCard(t("изображений создано", "images created"), fmt(g(StatsPrefs.IMAGES)), 0xFFCE93D8.toInt())
        ))
        val n = g(StatsPrefs.LATENCY_N)
        val avg = if (n > 0) String.format(java.util.Locale.US, "%.1f", g(StatsPrefs.LATENCY_MS) / n / 1000.0) + t(" с", " s") else "—"
        body.addView(pair(
            statCard(t("средний ответ", "average reply time"), avg, 0xFFFFB74D.toInt()),
            statCard(t("запросов с поиском", "searches"), fmt(g(StatsPrefs.SEARCHES)), 0xFF4FC3F7.toInt())
        ))
        body.addView(pair(
            statCard(t("запросов про код", "code requests"), fmt(g(StatsPrefs.CODE_REQ)), 0xFF80CBC4.toInt()),
            statCard(t("лайки / дизлайки", "likes / dislikes"), g(StatsPrefs.LIKES).toString() + " / " + g(StatsPrefs.DISLIKES), 0xFFE0E0E0.toInt())
        ))
        body.addView(pair(
            statCard(t("замен сломавшейся модели", "broken-model replacements"), fmt(g(StatsPrefs.FALLBACKS)), 0xFFFFD54F.toInt()),
            statCard(t("ошибок изображений", "image errors"), fmt(g(StatsPrefs.IMAGE_ERRORS)), 0xFFFF8A65.toInt())
        ))

        if (periodDays != 1) {
            val chartHead = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            chartHead.addView(section(t("Токены по дням", "Tokens per day")).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply { setColor(Th.bg(0xFF17171D.toInt())); cornerRadius = dp(12).toFloat() }
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            for ((mode, iconRes) in listOf(0 to R.drawable.ic_bar_chart, 1 to R.drawable.ic_trend_up, 2 to R.drawable.ic_trend_down)) {
                modeRow.addView(Ico.imageView(this, iconRes, Th.fg(if (mode == chartMode) 0xFFFFFFFF.toInt() else 0xFF9A9AA5.toInt())).apply {
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    layoutParams = LinearLayout.LayoutParams(dp(38), dp(30))
                    background = if (mode == chartMode) GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Th.bg(0xFF34343C.toInt())) } else null
                    contentDescription = when (mode) { 0 -> t("Столбцы", "Bars"); 1 -> t("Линия", "Line"); else -> t("Область", "Area") }
                    applyPressAnimation(PressAnimStyle.SOFT)
                    setOnClickListener {
                        if (chartMode == mode) return@setOnClickListener
                        chartMode = mode
                        try { getSharedPreferences("nanoai", Context.MODE_PRIVATE).edit().putInt("stats_chart_mode", mode).apply() } catch (_: Exception) {}
                        render()
                    }
                })
            }
            chartHead.addView(modeRow)
            body.addView(chartHead)
            body.addView(BarChart(this, snap.daily, chartMode).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
            })
        }

        if (snap.models.isNotEmpty()) {
            body.addView(section(t("Модели", "Models")))
            for (row in snap.models.take(8)) {
                body.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { setColor(Th.bg(0xFF16161C.toInt())); cornerRadius = dp(12).toFloat() }
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
                    addView(TextView(this@StatsActivity).apply {
                        text = row.name; textSize = 13.5f; setTextColor(Th.fg(0xFFFFFFFF.toInt())); maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(this@StatsActivity).apply {
                        text = row.requests.toString() + t(" зпр", " req") + (if (row.errors > 0) " · " + t("ошибок ", "errors ") + row.errors else "") +
                            " · ≈" + fmt(row.tokens) + " tok" + (if (row.avgMs > 0) " · " + String.format(java.util.Locale.US, "%.1f", row.avgMs / 1000.0) + t("с", "s") else "")
                        textSize = 11.5f; setTextColor(Th.fg(0xFF9A9AA5.toInt()))
                    })
                })
            }
            body.addView(TextView(this).apply {
                text = t("Статистика по моделям — за всё время.", "Per-model stats cover all time.")
                textSize = 11.5f; setTextColor(Th.fg(0xFF6E6E78.toInt())); setPadding(dp(4), dp(2), 0, dp(6))
            })
        }

        body.addView(TextView(this).apply {
            text = t("Сбросить статистику", "Reset statistics")
            textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFF6B6B.toInt()))
            background = GradientDrawable().apply { setColor(Th.bg(0xFF2A1618.toInt())); cornerRadius = dp(14).toFloat() }
            setPadding(0, dp(13), 0, dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
            applyPressAnimation(PressAnimStyle.LIFT)
            setOnClickListener {
                styledConfirmDialog(
                    title = t("Сбросить статистику?", "Reset statistics?"),
                    message = t("Все счётчики будут обнулены. Сами чаты не затрагиваются.", "All counters will be zeroed. Your chats are not affected."),
                    positiveText = t("Сбросить", "Reset"),
                    negativeText = t("Отмена", "Cancel"),
                    destructive = true
                ) { StatsPrefs.reset(this@StatsActivity); render() }
            }
        })
        body.addView(TextView(this).apply {
            text = t(
                "Токены — приблизительная оценка (≈ 1 токен на 4 символа). Данные хранятся только на устройстве и содержат одни числа.",
                "Tokens are an approximate estimate (≈ 1 token per 4 characters). Data is stored on the device only and contains numbers only."
            )
            textSize = 11.5f; setTextColor(Th.fg(0xFF6E6E78.toInt())); setPadding(dp(4), dp(10), dp(4), 0)
        })
    }

    private class BarChart(ctx: Context, private val data: List<Triple<String, Long, Long>>, private val mode: Int = 0) : View(ctx) {
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.fg(0xFFB39DFF.toInt()) }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Th.fg(0xFF9A9AA5.toInt()); textSize = 10f * ctx.resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
        }
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.bg(0xFF16161C.toInt()) }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Th.fg(0xFFB39DFF.toInt()); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.fg(0xFFB39DFF.toInt()) }
        private val dotHole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.bg(0xFF16161C.toInt()) }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Th.line(0xFF2A2A32.toInt()); strokeWidth = 1f }

        private fun drawLine(c: Canvas, d: Float, pad: Float, chartH: Float, slot: Float, maxV: Float, every: Int) {
            val xs = FloatArray(data.size) { pad + slot * it + slot / 2 }
            val ys = FloatArray(data.size) { i ->
                val v = data[i].second
                pad + chartH - (if (v == 0L) 0f else v / maxV * chartH)
            }
            for (k in 0..2) { val gy = pad + chartH * k / 2f; c.drawLine(pad, gy, width - pad, gy, grid) }
            val path = Path()
            path.moveTo(xs[0], ys[0])
            for (i in 1 until xs.size) {
                if (mode == 2) {
                    val mx = (xs[i - 1] + xs[i]) / 2f
                    path.cubicTo(mx, ys[i - 1], mx, ys[i], xs[i], ys[i])
                } else path.lineTo(xs[i], ys[i])
            }
            if (mode == 2) {
                val area = Path(path)
                area.lineTo(xs.last(), pad + chartH); area.lineTo(xs[0], pad + chartH); area.close()
                val base = Th.fg(0xFFB39DFF.toInt())
                fill.shader = LinearGradient(0f, pad, 0f, pad + chartH,
                    (base and 0x00FFFFFF) or 0x77000000, (base and 0x00FFFFFF) or 0x08000000, Shader.TileMode.CLAMP)
                c.drawPath(area, fill)
            }
            linePaint.strokeWidth = 2.5f * d
            c.drawPath(path, linePaint)
            if (mode == 1) for (i in xs.indices) {
                c.drawCircle(xs[i], ys[i], 4.5f * d, dotPaint)
                c.drawCircle(xs[i], ys[i], 2.2f * d, dotHole)
            }
            data.forEachIndexed { i, (label, _, _) ->
                if (i % every == 0 || i == data.lastIndex) c.drawText(label, xs[i], height - pad + 2 * d, txt)
            }
        }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 14 * d, 14 * d, bg)
            if (data.isEmpty()) return
            val maxV = (data.maxOf { it.second }).coerceAtLeast(1L).toFloat()
            val pad = 12 * d
            val labelH = 18 * d
            val slot = (width - 2 * pad) / data.size
            val bw = (slot * 0.62f).coerceAtMost(26 * d)
            val chartH = height - 2 * pad - labelH
            val every = if (data.size > 14) 5 else if (data.size > 7) 2 else 1
            if (mode != 0) { drawLine(c, d, pad, chartH, slot, maxV, every); return }
            data.forEachIndexed { i, (label, v, _) ->
                val cx = pad + slot * i + slot / 2
                val h = if (v == 0L) 2 * d else (v / maxV * chartH).coerceAtLeast(3 * d)
                val top = pad + chartH - h
                c.drawRoundRect(RectF(cx - bw / 2, top, cx + bw / 2, pad + chartH), 4 * d, 4 * d, bar)
                if (i % every == 0 || i == data.lastIndex) c.drawText(label, cx, height - pad + 2 * d, txt)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
