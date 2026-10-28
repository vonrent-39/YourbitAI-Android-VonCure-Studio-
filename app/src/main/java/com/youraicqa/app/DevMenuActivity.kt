package com.youraicqa.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class DevMenuActivity : NanoBaseActivity() {
    private val prefs by lazy { getSharedPreferences("nanoai", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AchievementsStore.onDevMenuOpened(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF000000.toInt()))
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        setContentView(root)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        head.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            setPadding(0, 0, dp(12), 0)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_dev_code)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(0); marginEnd = dp(8) }
        })
        head.addView(TextView(this).apply {
            text = "DevMenu"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(head)

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        body.addView(info("Продвинутые настройки Yourbit AI. Изменения применяются сразу."))

        body.addView(toggleCard(
            "Показывать токены",
            "Показывать оценку потраченных токенов над сообщениями ИИ.",
            prefs.getBoolean("dev_show_tokens", false)
        ) { checked ->
            prefs.edit().putBoolean("dev_show_tokens", checked).apply()
        })

        body.addView(actionCard("Системный промпт", systemPromptStatusText()) {
            showSystemPromptDialog()
        })

        body.addView(info(
            "Кастомный системный промпт работает только при наличии вашего собственного API-ключа. " +
                "Запрос идёт напрямую выбранному провайдеру, а сохранённый текст остаётся локально, " +
                "если ключ не задан. Никаких проверок содержимого промпта на джейлбрейк или блокировок " +
                "текста нет и не будет: это ваш промпт себе."
        ))

        body.addView(actionCard(
            "Шаблоны персоны",
            "Готовые, безопасные настройки характера — работают всегда, ключ не нужен."
        ) {
            showPersonaTemplatesDialog()
        })

        body.addView(info(
            "Диагностика reasoning в логах не раскрывает скрытую цепочку рассуждений модели. " +
                "В логах можно видеть только безопасные метаданные: что reasoning был получен/отфильтрован, " +
                "длительность и размеры ответа."
        ))

        body.addView(actionCard("Просмотреть логи", "Открыть последние записи работы клиента и ИИ") {
            showLogs()
        })
        body.addView(actionCard("Очистить логи", "Удалить журнал client.log") {
            styledConfirmDialog(
                title = "Очистить логи?",
                message = "Журнал client.log будет удалён без возможности восстановить.",
                positiveText = "Очистить",
                destructive = true
            ) {
                NanoLog.clear(this)
                Toast.makeText(this, Loc.t("Логи очищены"), Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun systemPromptStatusText(): String {
        val enabled = prefs.getBoolean("dev_custom_system_prompt_enabled", false)
        val hasText = prefs.getString("dev_custom_system_prompt", "").orEmpty().isNotBlank()
        if (!enabled || !hasText) return "Используется стандартный системный промпт."
        val provider = ApiKeyGate.lastUsedProvider(this)
        return if (provider != null && ApiKeyGate.hasOwnKey(this, provider)) {
            "Активен — текущая модель (${ApiKeyGate.providerLabel(provider)}) идёт через ваш ключ."
        } else {
            "Сохранён, но сейчас не используется: для текущей модели нет своего API-ключа."
        }
    }

    private val personaTemplates = listOf(
        "Формальный" to "Communicate formally and in a businesslike way: no slang, no emoji, short precise wording.",
        "Дружелюбный" to "Communicate warmly and informally, like a good buddy: a lively tone, an occasional fitting emoji.",
        "Краткий" to "Answer as tersely as possible — just the substance, no introductions or \"just in case\" explanations.",
        "Наставник" to "Explain step by step like a patient teacher: basics first, then details, with examples.",
        "С лёгкой иронией" to "You may add light, inoffensive irony and humor where appropriate, but not at the expense of the substance of the answer.",
        "Технарь" to "Use precise terminology for the topic without needless simplification, aim at a prepared reader."
    )

    private fun showPersonaTemplatesDialog() {
        val names = personaTemplates.map { it.first }
        styledListDialog("Шаблоны персоны", names) { which ->
            val (name, text) = personaTemplates[which]
            UiPrefs.setCustomPersona(this, text)
            Toast.makeText(this, Loc.t("Персона «$name» применена"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSystemPromptDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "Свой системный промпт полностью заменяет стандартный — если он пуст или выключен, " +
                "используется обычный характер Yourbit AI. Он применяется только для запросов, которые " +
                "идут через ваш собственный API-ключ."
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        })
        val enabledCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_settings_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val enabled = Switch(this).apply {
            isChecked = prefs.getBoolean("dev_custom_system_prompt_enabled", false)
        }
        val enabledCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        enabledCol.addView(TextView(this).apply {
            text = "Использовать мой системный промпт"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        enabledCol.addView(TextView(this).apply {
            text = "Полностью заменяет стандартный характер Yourbit AI на текст ниже."
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        enabledCard.addView(enabledCol)
        enabledCard.addView(enabled)
        box.addView(enabledCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        box.addView(TextView(this).apply {
            text = "ПРОМПТ"
            setTextColor(Th.fg(0xFF666666.toInt()))
            textSize = 11.5f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(6))
        })
        val edit = EditText(this).apply {
            hint = "Введите полный системный промпт…"
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            setTextColor(Th.fg(0xFFE8E8E8.toInt()))
            background = getDrawable(R.drawable.bg_input_field)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("dev_custom_system_prompt", ""))
            setTextIsSelectable(true)
        }
        val editScroll = ScrollView(this).apply {
            addView(edit, LinearLayout.LayoutParams(-1, -2))
        }
        box.addView(editScroll, LinearLayout.LayoutParams(-1, dp(240)))

        val counter = TextView(this).apply {
            text = "${edit.text.length} символов"
            setTextColor(Th.fg(0xFF666666.toInt()))
            textSize = 11.5f
            gravity = Gravity.END
            setPadding(0, dp(6), dp(2), 0)
        }
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                counter.text = "${s?.length ?: 0} символов"
            }
        })
        box.addView(counter)

        val dialogScroll = ScrollView(this).apply { addView(box) }

        styledPanelDialog(
            title = "Системный промпт",
            contentView = dialogScroll,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Очистить", keepOpen = true) { edit.setText("") },
                StyledDialogButton("Сохранить") {
                    prefs.edit()
                        .putBoolean("dev_custom_system_prompt_enabled", enabled.isChecked)
                        .putString("dev_custom_system_prompt", edit.text.toString())
                        .apply()
                    NanoLog.log(this, "DEVMENU system_prompt_changed enabled=${enabled.isChecked}")
                    Toast.makeText(this, Loc.t("Сохранено"), Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    private fun showLogs() {
        val text = NanoLog.read(this, 500).ifBlank { "Логи пока пусты." }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(Th.fg(0xFFDCDCDC.toInt()))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        styledPanelDialog(
            title = "Логи",
            contentView = ScrollView(this).apply { addView(tv) },
            buttons = listOf(StyledDialogButton("Закрыть") {})
        )
    }

    private fun info(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(Th.fg(0xFF888888.toInt()))
        textSize = 13f
        setPadding(dp(4), dp(8), dp(4), dp(14))
    }

    private fun actionCard(title: String, desc: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_settings_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            addView(TextView(this@DevMenuActivity).apply {
                text = title
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@DevMenuActivity).apply {
                text = desc
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun toggleCard(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_settings_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            val col = LinearLayout(this@DevMenuActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            col.addView(TextView(this@DevMenuActivity).apply {
                text = title
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            col.addView(TextView(this@DevMenuActivity).apply {
                text = desc
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
            addView(col)
            addView(Switch(this@DevMenuActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> onToggle(value) }
            })
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
