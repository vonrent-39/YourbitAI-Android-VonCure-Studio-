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

class AboutActivity : NanoBaseActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
        }
        titleBox.addView(TextView(this).apply {
            text = Loc.t("О приложении")
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        titleBox.addView(View(this).apply {
            setBackgroundResource(R.drawable.bg_accent_bar)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(6) }
        })
        header.addView(titleBox)
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll)

        val appCard = card()
        appCard.gravity = Gravity.CENTER_HORIZONTAL
        appCard.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_logo)
            setColorFilter(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_avatar_bot)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        })
        appCard.addView(TextView(this).apply {
            text = "Yourbit AI"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { null } ?: ""
        appCard.addView(TextView(this).apply {
            text = "v$ver · by VonCure Studio"
            textSize = 13f
            setTextColor(Th.fg(0xFF8A8A92.toInt()))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        })
        appCard.addView(TextView(this).apply {
            text = Loc.t(TAGLINE)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
            setTextColor(Th.fg(0xFFCFCFD6.toInt()))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        })
        appCard.addView(TextView(this).apply {
            text = Loc.t("Telegram-канал VonCure Studio")
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Th.fg(0xFFB39DFF.toInt()))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            isClickable = true
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TELEGRAM_URL))) }
                catch (_: Exception) { android.widget.Toast.makeText(this@AboutActivity, TELEGRAM_URL, android.widget.Toast.LENGTH_LONG).show() }
            }
        })
        content.addView(appCard)

        content.addView(section(R.drawable.ic_book, Loc.t("Возможности приложения"), bullets(FEATURES)))
        content.addView(section(R.drawable.ic_shield, Loc.t("Политика конфиденциальности"), numbered(PRIVACY_ITEMS, PRIVACY_NOTE)))
        content.addView(section(R.drawable.ic_doc, Loc.t("Правила использования"), numbered(USAGE_ITEMS, USAGE_NOTE)))
        content.addView(section(R.drawable.ic_chat, Loc.t("Связь и поддержка"), Loc.t(SUPPORT_TEXT)))
        content.addView(section(R.drawable.ic_smile, Loc.t("Спасибо этим людям!"), thanksText()))

        setContentView(root)
        if (UiPrefs.animationsEnabled(this)) {
            for (i in 0 until content.childCount) {
                val v = content.getChildAt(i)
                v.fadeSlideIn(60L * i, dp(14).toFloat())
            }
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_settings_card)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun section(iconRes: Int, title: String, body: String): View {
        val c = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            applyPressAnimation(PressAnimStyle.SOFT)
        }
        head.addView(ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Th.fg(0xFFFFFFFF.toInt()))
            setPadding(dp(11), dp(11), dp(11), dp(11))
            setBackgroundResource(R.drawable.bg_settings_icon)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        head.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) }
        })
        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(Th.fg(0xFF8A8A92.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        head.addView(chevron)
        c.addView(head)
        val bodyTv = TextView(this).apply {
            text = body
            textSize = 13.5f
            setLineSpacing(0f, 1.15f)
            setTextColor(Th.fg(0xFFCFCFD6.toInt()))
            setTextIsSelectable(true)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        }
        c.addView(bodyTv)
        head.setOnClickListener {
            val show = bodyTv.visibility != View.VISIBLE
            bodyTv.visibility = if (show) View.VISIBLE else View.GONE
            chevron.animate().cancel(); chevron.animate().rotation(if (show) 180f else 0f).setDuration(200).start()
            if (show && UiPrefs.animationsEnabled(this)) bodyTv.fadeInSafely() else { bodyTv.alpha = 1f }
        }
        return c
    }

    private fun numbered(items: List<String>, note: String): String =
        items.mapIndexed { i, t -> "${i + 1}. ${Loc.t(t)}" }.joinToString("\n") + "\n\n" + Loc.t(note)

    private fun bullets(items: List<String>): String = items.joinToString("\n") { "• " + Loc.t(it) }

    private fun thanksText(): String =
        THANKS.joinToString("\n") { "• $it — " + Loc.t("тестер") }

    companion object {
        const val TELEGRAM_URL = "https://t.me/VonCure_Studio"

        const val TAGLINE = "Клиент для общения с ИИ: свои ключи, свои модели, без посредников."

        val FEATURES = listOf(
            "Чат с моделями 14 провайдеров, а также со своими и локальными моделями.",
            "Арена и «Авторитетность»: сравнение моделей и их взаимная оценка.",
            "Агент во встроенном браузере: сам открывает сайты, нажимает и вводит текст.",
            "Изображения, видео, файлы и веб-поиск прямо в чате.",
            "Формулы, подсветка кода, предпросмотр HTML и SVG.",
            "Терминал, плагины и свои математические символы.",
            "Экспорт и импорт чатов, резервные копии аккаунта и управление сессиями.",
            "Экономия токенов: память чата, краткие ответы, лёгкие изображения.",
            "Темы, редактор оформления и 8 языков интерфейса."
        )

        val PRIVACY_ITEMS = listOf(
            "Клиент не собирает и не хранит ваши персональные данные. Исключение — идентификатор устройства и список сессий (без текста сообщений): они нужны разделу «Экспорт и импорт».",
            "Ключи API хранятся только на вашем устройстве и отправляются лишь тому провайдеру, для которого введены.",
            "Ваши переписки хранятся только на вашем устройстве.",
            "Часть данных обрабатывают выбранные вами провайдеры, серверы, сервисы и плагины — по их собственным правилам."
        )
        const val PRIVACY_NOTE = "Политика может обновляться. Актуальная версия публикуется в нашем Telegram-канале."

        val USAGE_ITEMS = listOf(
            "Модификации приложения разрешены.",
            "Нельзя создавать вредоносное ПО на основе нашего кода: это прямое нарушение правил.",
            "Запрещено красть аккаунты и API-ключи.",
            "Плагины не должны скрыто отправлять чаты, ключи или другие данные на сторонние серверы и обязаны заранее объявлять запрашиваемые разрешения.",
            "Запрещено распространять вредоносное ПО внутри плагинов.",
            "Ответственность за плагины несут их авторы.",
            "За установку плагинов, подключение серверов и установку локальных моделей отвечаете вы — как и авторы этих плагинов, серверов и моделей.",
            "Продажа плагинов и серверов разрешена, но они обязаны заранее объявлять запрашиваемые разрешения, куда уходят запросы и какие данные собираются.",
            "Приложение нельзя использовать в незаконных целях и для нарушения прав других людей. За ключи, расходы и соблюдение правил провайдеров ИИ отвечает пользователь.",
            "Проект рассчитан на аудиторию старше 18 лет.",
            "Мы не отвечаем за ваши запросы и за установленный сторонний контент. Продукт предоставляется «как есть»."
        )
        const val USAGE_NOTE = "Правила могут обновляться. Актуальная версия публикуется в нашем Telegram-канале."

        const val SUPPORT_TEXT = "Новости, обновления и обратная связь — в Telegram-канале VonCure Studio. " +
            "Если что-то работает не так, опишите проблему, укажите версию приложения и модель, с которой она возникла. " +
            "Раздел «FAQ» в настройках отвечает на самые частые вопросы."

        val THANKS = listOf("Spixstandup", "AlexPlayStation", "DenTenXD", "Neomikov")
    }
}
