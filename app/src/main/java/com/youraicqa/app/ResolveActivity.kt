package com.youraicqa.app

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
import android.widget.Toast

class ResolveActivity : NanoBaseActivity() {

    private lateinit var body: LinearLayout

    private fun t(ru: String, en: String) = Loc.pick(ru, en)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)
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
            setImageResource(R.drawable.ic_determination); setColorFilter(Th.fg(0xFFFF6B8A.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(8) }
        })
        header.addView(TextView(this).apply {
            text = t("Решимость", "Determination"); textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(header)
        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll)
        setContentView(root)
        render()
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Th.bg(0xFF16161C.toInt())); cornerRadius = dp(14).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }
    }

    private fun render() {
        body.removeAllViews()
        body.addView(TextView(this).apply {
            text = t(
                "Если модель №1 упрётся в лимит длины или вернёт ошибку, уже написанная часть ответа передаётся модели №2, и она продолжает с того же места. " +
                    "Если и она споткнётся — модель №3, и так по кругу: после последней список начинается с первой. Модель №1 — всегда та, что выбрана в чате.",
                "If model #1 hits a length limit or returns an error, the part of the answer already written is handed to model #2, which continues from the same place. " +
                    "If it fails too — model #3, and so on in a loop: after the last one the list starts again from the first. Model #1 is always the one selected in the chat."
            )
            textSize = 13f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, 0, 0, dp(14))
        })

        val sw = card()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = t("Включить «Решимость»", "Enable “Determination”"); textSize = 15f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        col.addView(TextView(this).apply {
            text = t("Каждое переключение на следующую модель — ещё один запрос (и его токены).", "Every switch to the next model is one more request (and its tokens).")
            textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, dp(3), 0, 0)
        })
        sw.addView(col)
        sw.addView(Switch(this).apply {
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
            thumbTintList = ColorStateList(st, intArrayOf(Th.fg(0xFFB39DFF.toInt()), Th.fg(0xFFB5B5BD.toInt())))
            trackTintList = ColorStateList(st, intArrayOf(Th.bg(0x99B39DFF.toInt()), Th.bg(0x66FFFFFF.toInt())))
            isChecked = ResolvePrefs.enabled(this@ResolveActivity)
            setOnCheckedChangeListener { _, c -> ResolvePrefs.setEnabled(this@ResolveActivity, c); render() }
        })
        body.addView(sw)

        val count = ResolvePrefs.count(this)
        val cnt = card()
        cnt.addView(TextView(this).apply {
            text = t("Количество моделей", "Number of models"); textSize = 15f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        cnt.addView(stepBtn("−", count > ResolvePrefs.MIN_COUNT) { ResolvePrefs.setCount(this, count - 1); render() })
        cnt.addView(TextView(this).apply {
            text = count.toString(); textSize = 18f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        cnt.addView(stepBtn("+", count < ResolvePrefs.MAX_COUNT) { ResolvePrefs.setCount(this, count + 1); render() })
        body.addView(cnt)

        val all = ModelAuthorityPrefs.availableModels(this).filter { !it.isImage }
        for (i in 0 until count) {
            val row = card().apply { applyPressAnimation(PressAnimStyle.LIFT) }
            row.addView(TextView(this).apply {
                text = (i + 1).toString(); textSize = 14f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF1A1A1E.toInt())
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == 0) 0xFFFFD54F.toInt() else 0xFFB0B0BA.toInt()) }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            if (i == 0) {
                info.addView(TextView(this).apply {
                    text = t("Модель, выбранная в чате", "The model selected in chat"); textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                })
                info.addView(TextView(this).apply {
                    text = t("Отвечает первой", "Answers first"); textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt()))
                })
            } else {
                val key = ResolvePrefs.slot(this, i)
                val m = all.firstOrNull { ModelAuthorityPrefs.keyOf(it) == key }
                info.addView(TextView(this).apply {
                    text = m?.name ?: t("Подобрать автоматически", "Pick automatically"); textSize = 14.5f
                    setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val ok = m == null || ModelAuthorityPrefs.hasCredentials(this, m)
                info.addView(TextView(this).apply {
                    text = when {
                        m == null -> t("Ближайшая рекомендованная модель с ключом", "The nearest recommended model that has a key")
                        ok -> Loc.t(ApiKeyGate.providerLabel(m.provider))
                        else -> t("⚠ нет ключа — будет заменена автоматически", "⚠ no key — will be replaced automatically")
                    }
                    textSize = 12f; setTextColor(Th.fg(if (ok) 0xFF9A9AA5.toInt() else 0xFFFFB74D.toInt()))
                })
                row.setOnClickListener {
                    showModelListPanel(
                        title = t("Модель №${i + 1}", "Model #${i + 1}"),
                        subtitle = t("Продолжит ответ, если предыдущая споткнётся", "Will continue the answer if the previous one fails"),
                        models = all,
                        noKey = { cand -> !ModelAuthorityPrefs.hasCredentials(this, cand) }
                    ) { picked ->
                        ResolvePrefs.setSlot(this, i, ModelAuthorityPrefs.keyOf(picked)); render()
                    }
                }
                row.addView(info)
                row.addView(TextView(this).apply {
                    text = "✕"; textSize = 16f; gravity = Gravity.CENTER
                    setTextColor(Th.fg(0xFF8A8A92.toInt()))
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    applyPressAnimation(PressAnimStyle.POP)
                    setOnClickListener {
                        if (ResolvePrefs.slot(this@ResolveActivity, i).isBlank()) {
                            Toast.makeText(this@ResolveActivity, t("Уже выбрано автоматически", "Already set to automatic"), Toast.LENGTH_SHORT).show()
                        } else { ResolvePrefs.setSlot(this@ResolveActivity, i, ""); render() }
                    }
                })
                body.addView(row)
                continue
            }
            row.addView(info)
            body.addView(row)
        }
        Loc.translateTree(body)
    }

    private fun stepBtn(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Th.fg(if (enabled) 0xFFE0E0E0.toInt() else 0xFF55555C.toInt()))
        background = GradientDrawable().apply { setColor(Th.bg(0xFF2A2A32.toInt())); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        if (enabled) { applyPressAnimation(PressAnimStyle.POP); setOnClickListener { onClick() } }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
