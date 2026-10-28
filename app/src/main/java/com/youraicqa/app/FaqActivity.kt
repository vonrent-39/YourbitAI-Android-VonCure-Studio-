package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class FaqActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    private var fromSettings = false
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)
        AchievementsStore.onTabOpened(this, "faq")

        fromSettings = true
        val prefs = getSharedPreferences("nanoai", MODE_PRIVATE)

        val btnBack = findViewById<View>(R.id.btnBack)
        val btnContinue = findViewById<TextView>(R.id.btnContinue)

        btnBack.visibility = View.VISIBLE
        btnBack.applyPressAnimation(PressAnimStyle.SOFT)
        btnBack.setOnClickListener { finishWithSeen() }

        val container = findViewById<LinearLayout>(R.id.faqContainer)
        val dm = resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()

        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Th.bg(0xFF3B2A6B.toInt()), Th.bg(0xFF1D2B5E.toInt()))
            ).apply { cornerRadius = dp(22).toFloat() }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(14) }
            addView(TextView(this@FaqActivity).apply {
                text = Loc.t("Частые вопросы")
                Ico.start(this, R.drawable.vi_sparkles, 24f, 0xFFFFFFFF.toInt(), 10f)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(0xFFFFFFFF.toInt()); textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@FaqActivity).apply {
                text = Loc.t("Коротко о ключах, моделях, чатах, Агенте, резервных копиях и других возможностях. Нажмите на вопрос, чтобы раскрыть ответ.")
                setTextColor(0xCCFFFFFF.toInt()); textSize = 13.5f
                setLineSpacing(0f, 1.15f)
                setPadding(0, dp(6), 0, 0)
            })
        })

        val search = android.widget.EditText(this).apply {
            hint = Loc.t("Поиск по вопросам")
            Ico.start(this, R.drawable.vi_search, 18f, Th.fg(0xFF777777.toInt()), 10f)
            setHintTextColor(Th.fg(0xFF777777.toInt())); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14.5f; setSingleLine(true)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Th.bg(0xFF1E1E26.toInt())); cornerRadius = dp(18).toFloat() }
            setPadding(dp(16), dp(11), dp(16), dp(11))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        container.addView(search)

        class Row(val section: View, val card: View, val haystack: String)
        val rows = ArrayList<Row>()
        val sectionViews = ArrayList<Pair<View, List<View>>>()

        for (cat in FaqContent.categories) {
            val iconRes = cat.icon; val title = cat.title
            val header = TextView(this).apply {
                text = Loc.t(title)
                gravity = android.view.Gravity.CENTER_VERTICAL
                Ico.start(this, iconRes, 18f, Th.fg(0xFFB39DFF.toInt()), 8f)
                setTextColor(Th.fg(0xFFB39DFF.toInt())); textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(10), 0, dp(8))
            }
            container.addView(header)
            val cards = ArrayList<View>()
            for ((q, a) in cat.items) {
                val qt = Loc.t(q); val at = Loc.t(a)
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = getDrawable(R.drawable.bg_settings_card)
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                }
                val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                head.addView(TextView(this).apply {
                    text = qt
                    setTextColor(Th.fg(0xFFFFFFFF.toInt())); textSize = 15.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                // Same chevron as the "About" screen
                val chev = android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_chevron_down)
                    setColorFilter(Th.fg(0xFF8A8A92.toInt()))
                    layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(10) }
                }
                head.addView(chev)
                val body = TextView(this).apply {
                    text = at
                    setTextColor(Th.fg(0xFFB8B8C2.toInt())); textSize = 14.5f
                    setLineSpacing(0f, 1.2f)
                    setPadding(0, dp(10), 0, 0)
                    visibility = View.GONE
                    setTextIsSelectable(true)
                }
                card.addView(head); card.addView(body)
                card.applyPressAnimation(PressAnimStyle.SOFT)
                card.setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    chev.animate().cancel(); chev.animate().rotation(if (open) 180f else 0f).setDuration(200).start()
                    if (open && UiPrefs.animationsEnabled(this)) body.fadeInSafely() else { body.alpha = 1f }
                }
                container.addView(card)
                cards.add(card)
                rows.add(Row(header, card, (qt + " " + at).lowercase()))
            }
            sectionViews.add(header to cards)
        }

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                for (r in rows) r.card.visibility = if (q.isEmpty() || r.haystack.contains(q)) View.VISIBLE else View.GONE
                for ((h, cards) in sectionViews) h.visibility = if (cards.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnContinue.text = "ПРОДОЛЖИТЬ"
        btnContinue.isEnabled = true
        btnContinue.alpha = 1f
        btnContinue.applyPressAnimation(PressAnimStyle.SOFT)
        btnContinue.setOnClickListener { finishWithSeen() }
    }

    private fun finishWithSeen() {
        getSharedPreferences("nanoai", MODE_PRIVATE).edit().putBoolean("faq_seen", true).apply()
        finish()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fromSettings) finishWithSeen()
    }
}
