package com.youraicqa.app

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class ModelAuthorityActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true

    private var task = ModelAuthorityPrefs.TASK_TEXT
    private lateinit var body: LinearLayout
    private val tabViews = HashMap<String, TextView>()

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
            setImageResource(R.drawable.ic_crown)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(4); marginEnd = dp(8) }
        })
        header.addView(TextView(this).apply {
            text = t("Авторитетность моделей в разных заданиях", "Model authority across tasks")
            textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
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

        content.addView(TextView(this).apply {
            text = t(
                "Назначьте модели, которым приложение доверяет в каждой задаче (текст, код, генерация и видимость изображений). Первая в списке — самая авторитетная. Если для задачи что-то назначено, она используется вместо модели из панели чата.",
                "Assign the models the app should trust for each kind of task (text, code, image generation and image vision). The first in the list is the most authoritative. If something is assigned to a task, it is used instead of the model chosen in the chat panel."
            )
            textSize = 13f; setTextColor(Th.fg(0xFF9A9AA5.toInt()))
            gravity = Gravity.START
            setPadding(0, 0, 0, dp(14))
        })

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            background = GradientDrawable().apply { setColor(Th.bg(0xFF17171D.toInt())); cornerRadius = dp(14).toFloat() }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(14) }
        }
        val labels = listOf(
            ModelAuthorityPrefs.TASK_TEXT to t("Текст", "Text"),
            ModelAuthorityPrefs.TASK_CODE to t("Код", "Code"),
            ModelAuthorityPrefs.TASK_IMAGE to t("Генерация изображений", "Image generation"),
            ModelAuthorityPrefs.TASK_VISION to t("Видимость изображений", "Image vision")
        )
        for ((id, label) in labels) {
            val tv = TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER; maxLines = 2
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                minHeight = dp(52)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener { task = id; refreshTabs(); render() }
            }
            tabViews[id] = tv
            tabRow.addView(tv)
        }
        content.addView(tabRow)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(body)

        setContentView(root)
        refreshTabs()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) render()
    }

    private fun refreshTabs() {
        for ((id, tv) in tabViews) {
            val sel = id == task
            tv.setTextColor(Th.fg(if (sel) 0xFFFFFFFF.toInt() else 0xFF8A8A92.toInt()))
            tv.background = if (sel) GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(Th.bg(0xFF34343C.toInt()))
            } else null
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Th.bg(0xFF16161C.toInt())); cornerRadius = dp(14).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) }
    }

    private fun iconBtn(label: CharSequence, enabled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 17f; gravity = Gravity.CENTER
        setTextColor(Th.fg(if (enabled) 0xFFE0E0E0.toInt() else 0xFF55555C.toInt()))
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
        if (enabled) {
            applyPressAnimation(PressAnimStyle.POP)
            setOnClickListener { onClick() }
        }
    }

    private fun render() {
        body.removeAllViews()
        val keys = ModelAuthorityPrefs.keys(this, task).toMutableList()
        val all = ModelAuthorityPrefs.modelsForTask(this, task)

        val auto = card()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = t("Автоподбор", "Auto-select"); textSize = 15f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        })
        col.addView(TextView(this).apply {
            text = t(
                "Если самая авторитетная модель сломалась (ошибка, перегрузка, таймаут), запрос уйдёт следующей по списку. Выключено — всегда используется только первая.",
                "If the most authoritative model breaks (error, overload, timeout), the request goes to the next one in the list. Off — only the first one is always used."
            )
            textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, dp(3), 0, 0)
        })
        auto.addView(col)
        auto.addView(Switch(this).apply {
            val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
            thumbTintList = ColorStateList(st, intArrayOf(Th.fg(0xFFB39DFF.toInt()), Th.fg(0xFFB5B5BD.toInt())))
            trackTintList = ColorStateList(st, intArrayOf(Th.bg(0x99B39DFF.toInt()), Th.bg(0x66FFFFFF.toInt())))
            isChecked = ModelAuthorityPrefs.autoPick(this@ModelAuthorityActivity, task)
            setOnCheckedChangeListener { _, c -> ModelAuthorityPrefs.setAutoPick(this@ModelAuthorityActivity, task, c) }
        })
        body.addView(auto)

        val resolved = keys.mapNotNull { k -> all.firstOrNull { ModelAuthorityPrefs.keyOf(it) == k }?.let { k to it } }
        if (resolved.isEmpty()) {
            body.addView(TextView(this).apply {
                text = t(
                    "Для этой задачи модели не назначены — используется модель из панели чата.",
                    "No models are assigned to this task — the model from the chat panel is used."
                )
                textSize = 13f; setTextColor(Th.fg(0xFF8A8A92.toInt())); gravity = Gravity.START; setPadding(0, dp(6), 0, dp(14))
            })
        }
        resolved.forEachIndexed { i, (k, m) ->
            val ok = ModelAuthorityPrefs.hasCredentials(this, m)
            val row = card()
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
            info.addView(TextView(this).apply {
                text = m.name; textSize = 14.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Th.fg(0xFFFFFFFF.toInt())); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = if (ok) Loc.t(ApiKeyGate.providerLabel(m.provider)) else t("⚠ нет ключа — будет пропущена", "⚠ no key — will be skipped")
                textSize = 12f; setTextColor(Th.fg(if (ok) 0xFF9A9AA5.toInt() else 0xFFFFB74D.toInt()))
            })
            row.addView(info)
            row.addView(iconBtn("↑", i > 0) { move(keys, k, -1) })
            row.addView(iconBtn("↓", i < resolved.lastIndex) { move(keys, k, +1) })
            row.addView(iconBtn(Ico.glyph(this, R.drawable.ic_close, 15f, Th.fg(0xFFE0E0E0.toInt()))) { keys.remove(k); save(keys) })
            body.addView(row)
        }

        val addable = all.filter { ModelAuthorityPrefs.keyOf(it) !in keys }
        body.addView(TextView(this).apply {
            text = t("+ Добавить модель", "+ Add model")
            textSize = 14.5f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Th.fg(0xFFB39DFF.toInt()))
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1A2E.toInt())); cornerRadius = dp(14).toFloat() }
            setPadding(0, dp(13), 0, dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            applyPressAnimation(PressAnimStyle.LIFT)
            setOnClickListener {
                if (addable.isEmpty()) {
                    android.widget.Toast.makeText(this@ModelAuthorityActivity, t("Все подходящие модели уже добавлены", "All suitable models are already added"), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    showModelListPanel(
                        title = t("Добавить модель", "Add model"),
                        subtitle = t("Выберите модель для этой задачи", "Pick a model for this task"),
                        models = addable,
                        noKey = { m -> !ModelAuthorityPrefs.hasCredentials(this@ModelAuthorityActivity, m) }
                    ) { m ->
                        keys.add(ModelAuthorityPrefs.keyOf(m)); save(keys)
                    }
                }
            }
        })
        if (task == ModelAuthorityPrefs.TASK_VISION) renderBuiltinModes()
        Loc.translateTree(body)
    }

    private fun renderBuiltinModes() {
        body.addView(TextView(this).apply {
            text = t("Режимы встроенных моделей", "Built-in model modes")
            textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setPadding(0, dp(22), 0, dp(4))
        })
        body.addView(TextView(this).apply {
            text = t(
                "Включите Vision у встроенной модели, если знаете, что она понимает картинки — она появится в списке выше и сможет разбирать изображения. Модели с Gemini/VL/Omni определяются автоматически.",
                "Turn on Vision for a built-in model if you know it understands images — it will appear in the list above and can analyse images. Gemini/VL/Omni models are detected automatically."
            )
            textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt())); setPadding(0, 0, 0, dp(10))
        })
        val enabled = ModelAuthorityPrefs.builtinVisionIds(this)
        for (b in BuiltinModels.list.filter { !it.isImage }) {
            val auto = ModelAuthorityPrefs.isVisionCapable(b) && b.id !in enabled
            val row = card()
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) }
            }
            info.addView(TextView(this).apply {
                text = b.name; textSize = 14.5f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Th.fg(0xFFFFFFFF.toInt())); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = if (auto) t("Vision определён автоматически", "Vision detected automatically") else Loc.t(ApiKeyGate.providerLabel(b.provider))
                textSize = 12f; setTextColor(Th.fg(0xFF9A9AA5.toInt()))
            })
            row.addView(info)
            row.addView(Switch(this).apply {
                val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked))
                thumbTintList = ColorStateList(st, intArrayOf(Th.fg(0xFFB39DFF.toInt()), Th.fg(0xFFB5B5BD.toInt())))
                trackTintList = ColorStateList(st, intArrayOf(Th.bg(0x99B39DFF.toInt()), Th.bg(0x66FFFFFF.toInt())))
                isChecked = auto || b.id in enabled
                isEnabled = !auto
                setOnCheckedChangeListener { _, c ->
                    ModelAuthorityPrefs.setBuiltinVision(this@ModelAuthorityActivity, b.id, c)
                    render()
                }
            })
            body.addView(row)
        }
    }

    private fun move(keys: MutableList<String>, k: String, delta: Int) {
        val i = keys.indexOf(k)
        val j = i + delta
        if (i < 0 || j !in keys.indices) return
        keys.removeAt(i); keys.add(j, k); save(keys)
    }

    private fun save(keys: List<String>) {
        ModelAuthorityPrefs.setKeys(this, task, keys)
        render()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
