package com.youraicqa.app

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

class LanguageActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true

    private data class Option(val code: String, val nativeName: String, val subtitle: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)

        val current = Loc.lang(this)
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
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_language)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(8) }
        })
        header.addView(TextView(this).apply {
            text = Loc.pick("Язык", "Language")
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll)

        content.addView(TextView(this).apply {
            text = Loc.pick(
                "Язык интерфейса приложения. Английский — язык по умолчанию. Ответы нейросети остаются на том языке, на котором вы с ней общаетесь.",
                "The app's interface language. English is the default. The AI's replies stay in whichever language you chat in."
            )
            textSize = 13f
            setTextColor(Th.fg(0xFF9A9AA5.toInt()))
            setPadding(dp(2), 0, dp(2), dp(14))
        })

        val options = listOf(
            Option(Loc.EN, "English", "Default"),
            Option(Loc.RU, "Русский", "Russian"),
            Option(Loc.ES, "Español", "Spanish"),
            Option(Loc.PT, "Português", "Portuguese"),
            Option(Loc.KK, "Қазақша", "Kazakh"),
            Option(Loc.UK, "Українська", "Ukrainian"),
            Option(Loc.BE, "Беларуская", "Belarusian"),
            Option(Loc.ZH, "中文（简体）", "Chinese")
        )
        for (opt in options) {
            val selected = opt.code == current
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Th.bg(if (selected) 0xFF1E1A2E.toInt() else 0xFF16161C.toInt()))
                    cornerRadius = dp(16).toFloat()
                    if (selected) setStroke(dp(2), Th.line(0xFFB39DFF.toInt()))
                }
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(12) }
                applyPressAnimation(PressAnimStyle.LIFT)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = opt.nativeName
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            })
            col.addView(TextView(this).apply {
                text = when (opt.code) {
                    Loc.EN -> Loc.pick("По умолчанию", "Default")
                    Loc.ES -> Loc.pick("Испанский язык", "Spanish")
                    Loc.PT -> Loc.pick("Португальский язык", "Portuguese")
                    Loc.KK -> Loc.pick("Казахский язык", "Kazakh")
                    Loc.UK -> Loc.pick("Украинский язык", "Ukrainian")
                    Loc.BE -> Loc.pick("Белорусский язык", "Belarusian")
                    Loc.ZH -> Loc.pick("Китайский язык", "Chinese (Simplified)")
                    else -> Loc.pick("Русский язык", "Russian")
                }
                textSize = 12.5f
                setTextColor(Th.fg(0xFF9A9AA5.toInt()))
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(col)
            card.addView(TextView(this).apply {
                text = if (selected) "✓" else ""
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Th.fg(0xFFB39DFF.toInt()))
            })
            card.setOnClickListener {
                if (opt.code == Loc.lang(this)) return@setOnClickListener
                Loc.setLang(this, opt.code)
                recreate()
            }
            content.addView(card)
        }

        setContentView(root)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
