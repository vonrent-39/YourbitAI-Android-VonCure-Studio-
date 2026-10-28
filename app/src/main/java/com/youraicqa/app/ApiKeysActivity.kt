package com.youraicqa.app

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*

class ApiKeysActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    private val prefs by lazy { getSharedPreferences("nanoai", MODE_PRIVATE) }

    private data class KeyField(
        val prefKey: String,
        val title: String,
        val hint: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_keys)
        AchievementsStore.onTabOpened(this, "apikeys")
        findViewById<View>(R.id.btnBack).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.keysContainer)
        val fields = listOf(
            KeyField("openrouter", "OpenRouter API Key", "Введите ваш API-ключ от OpenRouter"),
            KeyField("hf", "Hugging Face API key", "Введите ваш API-ключ от Hugging Face"),
            KeyField("groq", "Groq API key", "Введите ваш API-ключ от Groq"),
            KeyField("gemini", "Google AI Studio (Gemini)", "Введите ваш API-ключ от Google AI Studio"),
            KeyField("cloudflare", "Cloudflare API Token", "Workers AI — API Token (Bearer)"),
            KeyField("cloudflare_account", "Cloudflare Account ID", "ID аккаунта Cloudflare (для Workers AI)"),
            KeyField("llm7", "LLM7.io Token", "Необязательно: без токена — анонимный доступ с малыми лимитами (dash.llm7.io)"),
            KeyField("pollinations", "Pollinations API Key", "Ключ с enter.pollinations.ai"),
            KeyField("mistral", "Mistral AI API Key", "Введите ваш API-ключ от Mistral (console.mistral.ai)"),
            KeyField("cohere", "Cohere API Key", "Введите ваш API-ключ от Cohere (dashboard.cohere.com)"),
            KeyField("openai", "OpenAI API Key", "Платный ключ с platform.openai.com"),
            KeyField("qwen", "Qwen API Key (Alibaba Cloud Model Studio)", "Ключ Model Studio, международный регион (Сингапур)"),
            KeyField("deepseek", "DeepSeek API Key", "Ключ с platform.deepseek.com"),
            KeyField("anthropic", "Anthropic API Key", "Ключ Claude API с console.anthropic.com (через слой совместимости с OpenAI)"),
            KeyField("nvidia", "NVIDIA API Key", "Ключ с build.nvidia.com (NIM, начинается с nvapi-)")
        )
        for (f in fields) container.addView(makeKeyCard(f))

        container.addView(makeInfoCard())
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun makeKeyCard(field: KeyField): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_settings_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        card.addView(TextView(this).apply {
            text = field.title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = field.hint
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_input_field)
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        val edit = EditText(this).apply {
            setText(prefs.getString(field.prefKey, "") ?: "")
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            hint = "API key"
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            minHeight = dp(44)
            textSize = 15f
        }
        var visible = false
        val eye = ImageView(this).apply {
            setImageResource(R.drawable.ic_eye_off)
            setColorFilter(Th.fg(0xFFCCCCCC.toInt()))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            visibility = if (edit.text.isNullOrEmpty()) android.view.View.INVISIBLE else android.view.View.VISIBLE
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                visible = !visible
                edit.inputType = if (visible)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setImageResource(if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off)
                edit.setSelection(edit.text.length)
            }
        }
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                eye.visibility = if (s.isNullOrEmpty()) android.view.View.INVISIBLE else android.view.View.VISIBLE
            }
        })
        row.addView(edit)
        row.addView(eye)
        card.addView(row)

        val status = TextView(this).apply {
            setPadding(0, dp(10), 0, 0)
            textSize = 13f
        }
        fun refreshStatus() {
            val has = !edit.text.isNullOrBlank()
            if (has) {
                status.text = "●  Ключ сохранён"
                status.setTextColor(Th.fg(0xFF4CAF50.toInt()))
            } else {
                status.text = "○  Ключ не задан"
                status.setTextColor(Th.fg(0xFF777777.toInt()))
            }
        }
        refreshStatus()
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString(field.prefKey, s?.toString()?.trim() ?: "").apply()
                refreshStatus()
            }
        })
        card.addView(status)
        return card
    }

    private fun makeInfoCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            background = getDrawable(R.drawable.bg_settings_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "ⓘ"
            setTextColor(Th.fg(0xFFAAAAAA.toInt()))
            textSize = 16f
            setPadding(0, 0, dp(10), 0)
        })
        card.addView(TextView(this).apply {
            text = "Yourbit AI работает только с вашими API-ключами. Ключи хранятся локально на устройстве и отправляются напрямую выбранному провайдеру. Для своих OpenAI-совместимых API можно один раз добавить провайдера и использовать его ключ для нескольких моделей."
            setTextColor(Th.fg(0xFFAAAAAA.toInt()))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return card
    }

}
