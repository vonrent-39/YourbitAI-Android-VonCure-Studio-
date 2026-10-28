package com.youraicqa.app

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class EconomyActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true

    private lateinit var economySwitch: Switch
    private val toggleRefreshers = mutableListOf<() -> Unit>()
    private val toggleGetters = mutableListOf<() -> Boolean>()

    private fun checkAllTogglesOn() {
        if (toggleGetters.isNotEmpty() && toggleGetters.all { it() }) AchievementsStore.onEconomyAllOn(this)
    }
    private var syncingEconomySwitch = false

    override fun onResume() {
        super.onResume()
        if (::economySwitch.isInitialized) {
            syncingEconomySwitch = true
            economySwitch.isChecked = EconomyPrefs.economyModeEnabled(this)
            syncingEconomySwitch = false
        }
        toggleRefreshers.forEach { it() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF0B0B0E.toInt()))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
            setImageResource(R.drawable.ic_economy)
            setColorFilter(Th.fg(0xFF81C784.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(8) }
        })
        header.addView(TextView(this).apply {
            text = "Экономика"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll)

        val economyCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF16211A.toInt()))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Th.line(0xFF3E6B45.toInt()))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(18) }
        }
        val economyCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        economyCol.addView(TextView(this).apply {
            text = "Экономный режим"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        economyCol.addView(TextView(this).apply {
            text = "Разом включает самые весомые пункты из обеих вкладок ниже"
            textSize = 12.5f
            setTextColor(Th.fg(0xFF9AA79A.toInt()))
            setPadding(0, dp(3), 0, 0)
        })
        economyCard.addView(economyCol)
        economySwitch = Switch(this).apply { styleSwitch(this) }
        economyCard.addView(economySwitch)
        content.addView(economyCard)

        val tokensContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val energyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF17171D.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(14) }
        }
        fun tabButton(label: String, icon: Int): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ImageView(this@EconomyActivity).apply {
                setImageResource(icon)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
            })
            addView(TextView(this@EconomyActivity).apply {
                text = label
                textSize = 13.5f
                setTypeface(typeface, Typeface.BOLD)
            })
            applyPressAnimation(PressAnimStyle.SOFT)
        }
        val tabTokens = tabButton("Токены", R.drawable.ic_brain)
        val tabEnergy = tabButton("Энергопотребляемые", R.drawable.ic_battery)
        val tabs = listOf(tabTokens to tokensContainer, tabEnergy to energyContainer)
        fun selectTab(selected: LinearLayout) {
            tabs.forEach { (btn, view) ->
                val isSel = btn === selected
                view.visibility = if (isSel) View.VISIBLE else View.GONE
                val icon = btn.getChildAt(0) as ImageView
                val label = btn.getChildAt(1) as TextView
                val color = if (isSel) 0xFFFFFFFF.toInt() else 0xFF8A8A92.toInt()
                icon.setColorFilter(Th.fg(color))
                label.setTextColor(Th.fg(color))
                btn.background = if (isSel) GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Th.bg(0xFF34343C.toInt()))
                } else null
            }
        }
        tabTokens.setOnClickListener { selectTab(tabTokens) }
        tabEnergy.setOnClickListener { selectTab(tabEnergy) }
        tabRow.addView(tabTokens)
        tabRow.addView(tabEnergy)
        content.addView(tabRow)
        content.addView(tokensContainer)
        content.addView(energyContainer)
        selectTab(tabTokens)

        addMemorySection(tokensContainer)
        tokensContainer.addView(toggleRow(
            title = "Короткий контекст",
            desc = "Отправлять модели только последние ${EconomyPrefs.SHORT_CONTEXT_MESSAGES} сообщений вместо всей истории чата — меньше токенов на длинных диалогах, но модель хуже помнит начало разговора.",
            get = { EconomyPrefs.limitContext(this) },
            set = { EconomyPrefs.setLimitContext(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Один кадр из ГИФ",
            desc = "Вместо трёх кадров анимации (начало/середина/конец) отправлять модели только средний — втрое меньше токенов на вложенную ГИФку.",
            get = { EconomyPrefs.gifSingleFrame(this) },
            set = { EconomyPrefs.setGifSingleFrame(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Характер в системном промпте",
            desc = "Отключить отправку инструкций «Характера» (Кастомизация → Характер) в каждом запросе — экономит токены, если там написан длинный текст. Сами инструкции не удаляются, просто не отправляются.",
            get = { !EconomyPrefs.disablePersonaPrompt(this) },
            set = { EconomyPrefs.setDisablePersonaPrompt(this, !it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Память о пользователе",
            desc = "Отключить отправку того, что вы рассказали о себе (Кастомизация → «О себе»), в каждом запросе — независимо от тумблера «Характер» выше. Сами сведения не удаляются, просто не отправляются модели — на случай, если вдруг захочется временно скрыть их от конкретной модели/провайдера или просто сэкономить токены.",
            get = { !EconomyPrefs.disableMemoryPrompt(this) },
            set = { EconomyPrefs.setDisableMemoryPrompt(this, !it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Системный промпт",
            desc = "Полностью не отправлять системный промпт — не только характер, а вообще всё: имя YourAI, описание клиента, защиту от подмены инструкций, правила форматирования и т.д. Экономит больше всего токенов на каждом запросе (особенно заметно для локальных моделей на устройстве), но модель превращается в «сырой» ассистент без своей идентичности и части защиты — включай осознанно. Свой системный промпт из «Параметры для разработчиков» при выключенном тумблере всё равно не отправится — тут приоритет выше.",
            get = { !EconomyPrefs.disableSystemPrompt(this) },
            set = { EconomyPrefs.setDisableSystemPrompt(this, !it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Автоперебор моделей",
            desc = "При включённом «Автоподборе» в чате — пробовать другие модели, если основная перегружена. Каждая попытка — это отдельный запрос со своими токенами; выключите, чтобы платить только за одну попытку.",
            get = { !EconomyPrefs.disableAutoRetry(this) },
            set = { EconomyPrefs.setDisableAutoRetry(this, !it) }
        ))

        tokensContainer.addView(toggleRow(
            title = "Подсказки-продолжения",
            desc = "Просить модель дописывать под каждым ответом 2–3 варианта следующей реплики. Выключите — подсказок под ответами не будет, зато уйдёт кусок системного промпта и лишние токены в каждом ответе.",
            get = { EconomyPrefs.suggestionsEnabled(this) },
            set = { EconomyPrefs.setSuggestionsEnabled(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Автоназвание чатов",
            desc = "Придумывать название чата отдельным запросом к модели. Выключите — название берётся из первого сообщения без обращения к модели.",
            get = { EconomyPrefs.autoTitleEnabled(this) },
            set = { EconomyPrefs.setAutoTitleEnabled(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Усиление запроса для изображений",
            desc = "Дописывать к описанию картинки длинную англоязычную инструкцию (точность, анатомия, качество). Выключите — в генерацию уходит только ваш текст как есть.",
            get = { EconomyPrefs.imagePromptBoostEnabled(this) },
            set = { EconomyPrefs.setImagePromptBoostEnabled(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Короткая выдача поиска",
            desc = "Передавать модели из расширенного поиска только первые ${EconomyPrefs.SHORT_SEARCH_RESULTS} результата вместо всей выдачи — меньше токенов на вопросах с поиском.",
            get = { EconomyPrefs.shortSearch(this) },
            set = { EconomyPrefs.setShortSearch(this, it) }
        ))

        tokensContainer.addView(toggleRow(
            title = "Лёгкие изображения",
            desc = "Вложенные картинки, кадры ГИФ и скриншоты Агента уходят модели меньше (до 640 px, JPEG 60% вместо 1024 px и 80%) — меньше токенов на запросах с картинками, но мелкий текст читается хуже.",
            get = { EconomyPrefs.lightImages(this) },
            set = { EconomyPrefs.setLightImages(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Один раунд веб-поиска",
            desc = "Если модель сама просит поиск по сайтам — делать не 3 раунда, а один: меньше повторных запросов со всей историей и выдачей.",
            get = { EconomyPrefs.oneSearchRound(this) },
            set = { EconomyPrefs.setOneSearchRound(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Краткие ответы",
            desc = "Добавлять в системный промпт просьбу отвечать без вступлений и повторов вопроса. Ответы короче — меньше токенов на выходе; подробности модель даёт, только если попросить.",
            get = { EconomyPrefs.conciseAnswers(this) },
            set = { EconomyPrefs.setConciseAnswers(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Ограничить рассуждение",
            desc = "Просить модели «думать» поменьше (reasoning_effort = low) в чате и у Агента там, где API это поддерживает: Gemini 2.5/3, gpt-oss в Groq, OpenRouter. Скрытое рассуждение тоже тратит токены.",
            get = { EconomyPrefs.capReasoning(this) },
            set = { EconomyPrefs.setCapReasoning(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Короткий прогон Агента",
            desc = "Агент делает не больше ${EconomyPrefs.AGENT_SHORT_STEPS} шагов вместо ${AgentProtocol.MAX_STEPS}; каждый шаг — отдельный запрос к модели. Дальше можно написать «продолжай».",
            get = { EconomyPrefs.agentShortRun(this) },
            set = { EconomyPrefs.setAgentShortRun(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Короткое наблюдение Агента",
            desc = "На каждом шаге Агент получает меньше текста страницы (1200 символов вместо 3000) и не больше 40 элементов вместо 70.",
            get = { EconomyPrefs.agentShortPage(this) },
            set = { EconomyPrefs.setAgentShortPage(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Агент без скриншотов",
            desc = "Не прикладывать к шагам Агента скриншоты, даже если модель их видит: только текст страницы и список элементов. Самая сильная экономия, но Агент хуже ориентируется на сложных страницах. В «Экономный режим» не входит.",
            get = { EconomyPrefs.agentTextOnly(this) },
            set = { EconomyPrefs.setAgentTextOnly(this, it) }
        ))
        tokensContainer.addView(toggleRow(
            title = "Сжимать старый код в истории",
            desc = "Длинные блоки кода из старых ответов (кроме последних сообщений) заменяются короткой пометкой при отправке модели. В чате код остаётся; модель не увидит старый листинг.",
            get = { EconomyPrefs.stripOldCode(this) },
            set = { EconomyPrefs.setStripOldCode(this, it) }
        ))

        tokensContainer.addView(tokenLimitCard())

        energyContainer.addView(toggleRow(
            title = "Анимация печати ответа",
            desc = "Показывать ответ ассистента печатающимся по буквам. То же самое, что переключатель в Кастомизация → Анимация ответов.",
            get = { EconomyPrefs.typingAnimationEnabled(this) },
            set = { EconomyPrefs.setTypingAnimationEnabled(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Размытие",
            desc = "Размытие зоны под капсулой ввода и фона за диалогами. Самый тяжёлый для видеочипа эффект — выключение заметно экономит батарею на слабых телефонах.",
            get = { EconomyPrefs.blurEnabled(this) },
            set = { EconomyPrefs.setBlurEnabled(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Свечение кнопок",
            desc = "Цветная тень-подсветка вокруг кнопки отправки, кнопок выбора модели и иконки ассистента. Чисто декоративно.",
            get = { EconomyPrefs.glowEnabled(this) },
            set = { EconomyPrefs.setGlowEnabled(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Анимации переходов",
            desc = "Плавные переходы между экранами и появление диалогов. Выключено — экраны и окна открываются мгновенно.",
            get = { EconomyPrefs.transitionsEnabled(this) },
            set = { EconomyPrefs.setTransitionsEnabled(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Подсветка кода",
            desc = "Раскраска синтаксиса в блоках кода и просмотре файлов. На длинных листингах заметно грузит процессор.",
            get = { EconomyPrefs.syntaxHighlightEnabled(this) },
            set = { EconomyPrefs.setSyntaxHighlightEnabled(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Реже обновлять текст при печати",
            desc = "Растущий ответ при потоковой выдаче перерисовывается не чаще двух раз в секунду. Меньше нагрузка на процессор и батарею, текст появляется чуть более рывками.",
            get = { EconomyPrefs.throttleStreaming(this) },
            set = { EconomyPrefs.setThrottleStreaming(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Реже опрашивать сервер моделей",
            desc = "Пока приложение открыто, список моделей с сервера проверяется в 4 раза реже (обычно раз в 15–20 секунд). Меньше сетевой активности и батареи, но правки на сервере появляются с задержкой.",
            get = { EconomyPrefs.slowModelSync(this) },
            set = { EconomyPrefs.setSlowModelSync(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Пульсация ожидания",
            desc = "Бесконечно прыгающие точки на заставке и мигание индикаторов ожидания в Арене. Выключено — индикаторы стоят на месте.",
            get = { EconomyPrefs.pulseEnabled(this) },
            set = { EconomyPrefs.setPulseEnabled(this, it) }
        ))
        energyContainer.addView(toggleRow(
            title = "Анимации нажатий",
            desc = "Пружинные/затемняющие эффекты на кнопках по всему приложению. Чисто декоративный слой — выключение не влияет на функциональность.",
            get = { EconomyPrefs.pressAnimationsEnabled(this) },
            set = { EconomyPrefs.setPressAnimationsEnabled(this, it) },
            last = true
        ))

        economySwitch.isChecked = EconomyPrefs.economyModeEnabled(this)
        economySwitch.setOnCheckedChangeListener { _, checked ->
            if (syncingEconomySwitch) return@setOnCheckedChangeListener
            EconomyPrefs.setEconomyMode(this, checked)
            toggleRefreshers.forEach { it() }
            checkAllTogglesOn()
        }

        setContentView(root)
    }

    private fun toggleRow(
        title: String,
        desc: String,
        get: () -> Boolean,
        set: (Boolean) -> Unit,
        last: Boolean = false
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF16161C.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = if (last) dp(4) else dp(10) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        col.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(Th.fg(0xFF9A9AA5.toInt()))
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(col)
        val sw = Switch(this).apply {
            styleSwitch(this)
            isChecked = get()
        }
        var syncing = false
        toggleGetters.add(get)
        toggleRefreshers.add {
            syncing = true
            sw.isChecked = get()
            syncing = false
        }
        sw.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            set(checked)
            val actual = get()
            if (actual != checked) {
                syncing = true
                sw.isChecked = actual
                syncing = false
            }
            checkAllTogglesOn()
        }
        row.addView(sw)
        return row
    }

    private fun tokenLimitCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF16161C.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(4) }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = "Общий лимит токенов на ответ"
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        col.addView(TextView(this).apply {
            text = "Одно значение max_tokens сразу для всех моделей, у которых не задан свой ручной лимит."
            textSize = 12f
            setTextColor(Th.fg(0xFF9A9AA5.toInt()))
            setPadding(0, dp(3), 0, 0)
        })
        headerRow.addView(col)
        val sw = Switch(this).apply {
            styleSwitch(this)
            isChecked = EconomyPrefs.globalTokenLimitEnabled(this@EconomyActivity)
        }
        headerRow.addView(sw)
        card.addView(headerRow)

        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (sw.isChecked) View.VISIBLE else View.GONE
            setPadding(0, dp(10), 0, 0)
        }
        val valueField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(EconomyPrefs.globalTokenLimitValue(this@EconomyActivity).toString())
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            hint = "напр. 4096"
            background = getDrawable(R.drawable.bg_input_field)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        valueRow.addView(valueField)
        valueRow.addView(TextView(this).apply {
            text = "токенов"
            textSize = 13f
            setTextColor(Th.fg(0xFF9A9AA5.toInt()))
            setPadding(dp(8), 0, 0, 0)
        })
        card.addView(valueRow)

        fun saveValue() {
            val v = valueField.text.toString().trim().toIntOrNull()
            if (v == null || v <= 0) {
                Toast.makeText(this, Loc.t("Введите целое число токенов больше нуля"), Toast.LENGTH_SHORT).show()
                valueField.setText(EconomyPrefs.globalTokenLimitValue(this).toString())
            } else {
                EconomyPrefs.setGlobalTokenLimitValue(this, v)
            }
        }
        valueField.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveValue() }

        sw.setOnCheckedChangeListener { _, checked ->
            EconomyPrefs.setGlobalTokenLimitEnabled(this, checked)
            valueRow.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) saveValue()
        }

        card.addView(TextView(this).apply {
            text = "Нужен лимит только у отдельных моделей? Настройте его вручную в форме модели →"
            textSize = 12f
            setTextColor(Th.fg(0xFF8AB4F8.toInt()))
            setPadding(0, dp(12), 0, 0)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                startActivity(android.content.Intent(this@EconomyActivity, ModelsActivity::class.java))
            }
        })

        return card
    }

    private class Stepper(val view: View, val refresh: () -> Unit)

    private fun cardBox(bottom: Int = 10): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Th.bg(0xFF16161C.toInt()))
            cornerRadius = dp(14).toFloat()
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(bottom) }
    }

    private fun titleView(t: String) = TextView(this).apply {
        text = t
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Th.fg(0xFFFFFFFF.toInt()))
    }

    private fun descView(t: String, top: Int = 3) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(Th.fg(0xFF9A9AA5.toInt()))
        setPadding(0, dp(top), 0, 0)
    }

    /** [-] value [+] with a slider; `unit` is the slider granularity, `stepOf` the button step at a given value. */
    private fun stepper(
        min: Int, max: Int, unit: Int,
        stepOf: (Int) -> Int,
        get: () -> Int, set: (Int) -> Unit,
        fmt: (Int) -> String,
        onChange: () -> Unit = {}
    ): Stepper {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        fun roundButton(label: String) = TextView(this).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            background = GradientDrawable().apply { setColor(Th.bg(0xFF2A2A33.toInt())); cornerRadius = dp(12).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(40))
            applyPressAnimation(PressAnimStyle.SOFT)
        }
        val minus = roundButton("−")
        val plus = roundButton("+")
        val valueTv = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(minus); addView(valueTv); addView(plus)
        }
        val seek = SeekBar(this).apply {
            this.max = (max - min) / unit
            thumbTintList = ColorStateList.valueOf(Th.fg(0xFFB39DFF.toInt()))
            progressTintList = ColorStateList.valueOf(Th.fg(0xFFB39DFF.toInt()))
            progressBackgroundTintList = ColorStateList.valueOf(Th.bg(0x44FFFFFF))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        }
        box.addView(row); box.addView(seek)

        var syncing = false
        fun refresh() {
            syncing = true
            val v = get().coerceIn(min, max)
            valueTv.text = fmt(v)
            seek.progress = (v - min) / unit
            syncing = false
        }
        fun commit(v: Int) {
            set(v.coerceIn(min, max))
            refresh()
            onChange()
        }
        minus.setOnClickListener { val v = get(); commit(v - stepOf(v - 1)) }
        plus.setOnClickListener { val v = get(); commit(v + stepOf(v)) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (syncing || !fromUser) return
                val v = min + p * unit
                set(v)
                valueTv.text = fmt(v)
                onChange()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        refresh()
        return Stepper(box, ::refresh)
    }

    private fun currentChatHistory(): org.json.JSONArray = try {
        val p = getSharedPreferences("nanoai", MODE_PRIVATE)
        val id = p.getString("current_chat_id", "").orEmpty()
        org.json.JSONArray(p.getString("chat_${id}_messages", "[]"))
    } catch (_: Exception) {
        org.json.JSONArray()
    }

    private fun messagesWord(n: Int): String {
        val m100 = n % 100; val m10 = n % 10
        return when {
            m100 in 11..14 -> "сообщений"
            m10 == 1 -> "сообщение"
            m10 in 2..4 -> "сообщения"
            else -> "сообщений"
        }
    }

    /** Chat memory block: window size, presets, first-message pin, trimming of old messages. */
    private fun addMemorySection(parent: LinearLayout) {
        val refreshers = mutableListOf<() -> Unit>()

        val card = cardBox()
        card.addView(titleView("Память чата"))
        card.addView(descView("Сколько последних сообщений уходит модели вместе с новым. Больше — лучше помнит разговор, но дороже каждый запрос. «Короткий контекст» ниже всё равно ограничивает это число."))

        val estimate = descView("", 8)
        fun refreshEstimate() {
            val hist = currentChatHistory()
            estimate.text = if (hist.length() == 0) "В текущем чате пока нет сообщений"
            else "≈ ${EconomyPrefs.estimateContextTokens(this, hist)} токенов истории в следующем запросе (текущий чат)"
        }

        val memStepper = stepper(
            min = EconomyPrefs.MEMORY_MIN, max = EconomyPrefs.MEMORY_MAX, unit = 1,
            stepOf = { v -> if (v < 20) 1 else if (v < 60) 5 else 10 },
            get = { EconomyPrefs.contextMessages(this).let { if (it == 0) EconomyPrefs.NORMAL_CONTEXT_MESSAGES else it } },
            set = { EconomyPrefs.setContextMessages(this, it) },
            fmt = { "$it ${messagesWord(it)}" },
            onChange = { refreshEstimate(); refreshers.forEach { r -> r() } }
        )

        val presets = listOf(6, 12, 20, 40, 80, 0)
        val chips = presets.map { v ->
            TextView(this).apply {
                text = if (v == 0) "Весь" else v.toString()
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(3); marginEnd = dp(3) }
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener {
                    EconomyPrefs.setContextMessages(this@EconomyActivity, v)
                    refreshers.forEach { r -> r() }
                }
            }
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
            chips.forEach { addView(it) }
        }
        card.addView(chipRow)
        card.addView(memStepper.view)
        card.addView(estimate)
        parent.addView(card)

        refreshers.add {
            val cur = EconomyPrefs.contextMessages(this)
            chips.forEachIndexed { i, c ->
                val sel = presets[i] == cur
                c.setTextColor(Th.fg(if (sel) 0xFFFFFFFF.toInt() else 0xFF9A9AA5.toInt()))
                c.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Th.bg(if (sel) 0xFF4B3A8F.toInt() else 0xFF2A2A33.toInt()))
                }
            }
            memStepper.view.visibility = if (cur == 0) View.GONE else View.VISIBLE
            memStepper.refresh()
            refreshEstimate()
        }

        parent.addView(toggleRow(
            title = "Помнить начало чата",
            desc = "Когда старые сообщения уже не влезают в память, первое сообщение пользователя (до 600 символов) всё равно передаётся модели через системный промпт — она не теряет исходную задачу в длинном диалоге.",
            get = { EconomyPrefs.keepFirstMessage(this) },
            set = { EconomyPrefs.setKeepFirstMessage(this, it); refreshEstimate() }
        ))

        val trimBox = cardBox()
        trimBox.addView(titleView("Порог сокращения"))
        trimBox.addView(descView("Старое сообщение длиннее этого порога отправляется модели без середины: остаются начало и конец."))
        val trimStepper = stepper(
            min = EconomyPrefs.OLD_CHARS_MIN, max = EconomyPrefs.OLD_CHARS_MAX, unit = 250,
            stepOf = { 250 },
            get = { EconomyPrefs.oldMessageMaxChars(this) },
            set = { EconomyPrefs.setOldMessageMaxChars(this, it) },
            fmt = { "$it символов" },
            onChange = { refreshEstimate() }
        )
        trimBox.addView(trimStepper.view)
        fun syncTrimBox() { trimBox.visibility = if (EconomyPrefs.trimLongMessages(this)) View.VISIBLE else View.GONE }
        parent.addView(toggleRow(
            title = "Сокращать длинные старые сообщения",
            desc = "Из старых длинных сообщений убирается середина перед отправкой модели. В самом чате текст остаётся целым.",
            get = { EconomyPrefs.trimLongMessages(this) },
            set = { EconomyPrefs.setTrimLongMessages(this, it); syncTrimBox(); refreshEstimate() }
        ))
        parent.addView(trimBox)
        refreshers.add { syncTrimBox(); trimStepper.refresh() }

        val freshCard = cardBox()
        freshCard.addView(titleView("Свежие сообщения без сжатия"))
        freshCard.addView(descView("Сколько последних сообщений всегда уходят целыми: к ним не применяются сокращение и сжатие кода."))
        val freshStepper = stepper(
            min = EconomyPrefs.FRESH_MIN, max = EconomyPrefs.FRESH_MAX, unit = 1,
            stepOf = { 1 },
            get = { EconomyPrefs.freshMessages(this) },
            set = { EconomyPrefs.setFreshMessages(this, it) },
            fmt = { "$it ${messagesWord(it)}" },
            onChange = { refreshEstimate() }
        )
        freshCard.addView(freshStepper.view)
        parent.addView(freshCard)
        refreshers.add { freshStepper.refresh() }

        // The activity refreshes everything on resume and when economy mode flips
        toggleRefreshers.add { refreshers.forEach { r -> r() } }
        refreshers.forEach { r -> r() }
    }

    private fun styleSwitch(sw: Switch) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        sw.thumbTintList = ColorStateList(states, intArrayOf(Th.fg(0xFFB39DFF.toInt()), Th.fg(0xFFB5B5BD.toInt())))
        sw.trackTintList = ColorStateList(states, intArrayOf(Th.bg(0x99B39DFF.toInt()), Th.bg(0x66FFFFFF.toInt())))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
