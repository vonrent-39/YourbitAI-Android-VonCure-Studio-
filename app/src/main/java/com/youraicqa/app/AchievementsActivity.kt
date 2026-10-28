package com.youraicqa.app

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class AchievementsActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    private lateinit var container: LinearLayout
    private lateinit var counter: TextView

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_achievements)
        findViewById<View>(R.id.btnBack).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        val font = AchievementsStore.font(this)
        findViewById<TextView>(R.id.achTitle).typeface = font
        counter = findViewById(R.id.achCounter)
        counter.typeface = font
        container = findViewById(R.id.achContainer)
    }

    override fun onResume() {
        super.onResume()
        AchievementsStore.refreshDerived(this)
        refresh()
    }

    private fun refresh() {
        val font = AchievementsStore.font(this)
        container.removeAllViews()
        val visible = AchievementsStore.ALL.filter { AchievementsStore.isVisible(this, it) }
        counter.text = "${AchievementsStore.unlockedCount(this)}/${AchievementsStore.ALL.size}"
        visible.forEach { container.addView(card(it, font)) }
    }

    private fun card(a: Achievement, font: Typeface): View {
        val done = AchievementsStore.isUnlocked(this, a.id)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_chat_row_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            alpha = if (done) 1f else 0.55f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = a.id.toString()
            typeface = font
            textSize = if (a.id >= 10) 17f else 22f
            gravity = Gravity.CENTER
            setTextColor(if (done) 0xFFFFFFFF.toInt() else Th.fg(0xFFAAAAAA.toInt()))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                if (done) setColor(0xFF7C5CE0.toInt()) else {
                    setColor(Th.bg(0xFF1E1E26.toInt()))
                    setStroke(dp(1), Th.fg(0xFF555555.toInt()))
                }
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = Loc.t(a.title)
            typeface = font
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15f
        })
        col.addView(TextView(this).apply {
            text = Loc.t(a.description)
            typeface = font
            setTextColor(Th.fg(0xFFB5B5BF.toInt()))
            textSize = 12.5f
            setPadding(0, dp(4), 0, 0)
        })
        if (a.hint.isNotBlank() && !done) {
            col.addView(TextView(this).apply {
                text = a.hint
                typeface = font
                setTextColor(Th.fg(0xFF777777.toInt()))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            })
        }
        card.addView(col)
        return card
    }
}
