package com.youraicqa.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class AdvancedSettingsActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true

    private lateinit var content: LinearLayout

    private fun t(ru: String, en: String) = Loc.pick(ru, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF0B0B0E.toInt()))
        }
        root.addView(buildHeader(R.drawable.ic_layers, t("Дополнительные настройки", "Advanced settings")))
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    private fun render() {
        content.removeAllViews()

        section(t("Ответы", "Replies"))
        toggleCard(
            title = t("Строгий режим правил", "Strict rules mode"),
            desc = t(
                "К системному промпту добавляется жёсткий блок: ИИ обязан соблюдать ВСЕ правила, сверять черновик с ними перед ответом и не бросать ответ на полуслове.",
                "A strict block is added to the system prompt: the AI must follow ALL the rules, check its draft against them before answering and never stop mid-answer."
            ),
            get = { AdvancedPrefs.strictRules(this) },
            set = { AdvancedPrefs.setStrictRules(this, it) }
        )
        toggleCard(
            title = t("Склейка продолжений без повторов", "Merge continuations without repeats"),
            desc = t(
                "Когда ответ дописывается («Продолжить», «Решимость»), повторённый моделью хвост, начало или блок кода убираются — текст не дублируется.",
                "When a reply is continued (“Continue”, “Determination”), a tail, start or code block repeated by the model is removed — the text isn't duplicated."
            ),
            get = { AdvancedPrefs.dedupeContinue(this) },
            set = { AdvancedPrefs.setDedupeContinue(this, it) }
        )

        section(t("Интерфейс", "Interface"))
        toggleCard(
            title = t("Подсветка нажатий", "Press highlight"),
            desc = t(
                "Мягкая вспышка поверх кнопки при нажатии — заметна и в светлой теме. Применяется при следующем открытии экрана.",
                "A soft flash over a button when pressed — visible in the light theme too. Applied the next time a screen opens."
            ),
            get = { AdvancedPrefs.pressHighlight(this) },
            set = { AdvancedPrefs.setPressHighlight(this, it) }
        )

        section(t("Модификации", "Modifications"))
        val on = ResolvePrefs.enabled(this)
        val status = if (on) t("Включена · моделей: ${ResolvePrefs.count(this)}", "On · models: ${ResolvePrefs.count(this)}") else t("Выключена", "Off")
        val card = card().apply {
            applyPressAnimation(PressAnimStyle.LIFT)
            setOnClickListener { startActivity(Intent(this@AdvancedSettingsActivity, ResolveActivity::class.java)) }
        }
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_determination)
            setColorFilter(Th.fg(0xFFFF6B8A.toInt()))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Th.bg(0x33FF6B8A)) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = t("Решимость", "Determination"); textSize = 16f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        col.addView(TextView(this).apply {
            text = t(
                "Если модель упёрлась в лимит или ошибку, её ответ дописывает следующая модель — по кругу.",
                "If a model hits a limit or an error, the next model finishes its answer — in a loop."
            )
            textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, dp(3), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = status; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(if (on) 0xFF7CFFB2.toInt() else 0xFF8A8A92.toInt())); setPadding(0, dp(5), 0, 0)
        })
        card.addView(col)
        card.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(Th.fg(0xFF8A8A92.toInt())) })
        content.addView(card)
        Loc.translateTree(content)
    }

    private fun buildHeader(iconRes: Int, title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(12), dp(12), dp(4))
        addView(TextView(this@AdvancedSettingsActivity).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt())); textSize = 22f; gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        addView(ImageView(this@AdvancedSettingsActivity).apply {
            setImageResource(iconRes); setColorFilter(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(8) }
        })
        addView(TextView(this@AdvancedSettingsActivity).apply {
            text = title; textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title.uppercase(); textSize = 12f; letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFF8A8A92.toInt()))
            setPadding(dp(2), dp(16), 0, dp(8))
        })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Th.bg(0xFF16161C.toInt())); cornerRadius = dp(14).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }
    }

    private fun toggleCard(title: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val row = card()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = title; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        col.addView(TextView(this).apply {
            text = desc; textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, dp(3), 0, 0)
        })
        row.addView(col)
        row.addView(styledSwitch(get(), set))
        content.addView(row)
    }

    private fun styledSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
        thumbTintList = ColorStateList(st, intArrayOf(Th.fg(0xFFB39DFF.toInt()), Th.fg(0xFFB5B5BD.toInt())))
        trackTintList = ColorStateList(st, intArrayOf(Th.bg(0x99B39DFF.toInt()), Th.bg(0x66FFFFFF.toInt())))
        isChecked = checked
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
