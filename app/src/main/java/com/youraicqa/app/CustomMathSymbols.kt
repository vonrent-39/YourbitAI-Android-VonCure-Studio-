package com.youraicqa.app

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

object CustomMathSymbols {
    private const val PREFS = "nanoai"
    private const val KEY = "custom_math_symbols"
    private val NAME = Regex("^[A-Za-z]{1,32}$")

    @Volatile private var cache: Map<String, String> = emptyMap()

    fun load(context: Context) {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        cache = parse(raw)
    }

    internal fun parse(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, String>()
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = o.optString(k)
                if (NAME.matches(k) && v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun all(): Map<String, String> = cache

    fun lookup(name: String): String? = cache[name]

    fun isValidName(name: String): Boolean = NAME.matches(name)

    private fun save(context: Context, map: Map<String, String>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
        cache = LinkedHashMap(map)
    }

    fun set(context: Context, name: String, replacement: String): Boolean {
        if (!isValidName(name) || replacement.isEmpty()) return false
        val m = LinkedHashMap(cache)
        m[name] = replacement
        save(context, m)
        return true
    }

    fun remove(context: Context, name: String) {
        val m = LinkedHashMap(cache)
        m.remove(name)
        save(context, m)
    }

    fun showEditor(activity: NanoBaseActivity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var dialog: android.app.Dialog? = null

        fun field(hintText: String) = EditText(activity).apply {
            hint = hintText
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            background = GradientDrawable().apply { setColor(Th.bg(0xFF1E1E26.toInt())); cornerRadius = dp(12).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
        }

        fun rebuild() {
            box.removeAllViews()
            box.addView(TextView(activity).apply {
                text = "Свои символы работают в формулах: напишите \\имя — и оно заменится на заданный символ. " +
                    "Например, имя «mything» и символ «★» → \\mything даёт ★. Свои записи важнее встроенных."
                setTextColor(Th.fg(0xFF9A9AA5.toInt()))
                textSize = 12.5f
                setPadding(0, 0, 0, dp(10))
            })
            val items = cache.entries.toList()
            if (items.isEmpty()) {
                box.addView(TextView(activity).apply {
                    text = "Пока пусто."
                    setTextColor(Th.fg(0xFF777777.toInt()))
                    textSize = 13f
                    setPadding(0, 0, 0, dp(8))
                })
            }
            for ((k, v) in items) {
                box.addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                    addView(TextView(activity).apply {
                        text = "\\$k  →  $v"
                        typeface = Typeface.MONOSPACE
                        setTextColor(Th.fg(0xFFE8E8E8.toInt()))
                        textSize = 14.5f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(activity).apply {
                        text = "✕"
                        setTextColor(Th.fg(0xFFE57373.toInt()))
                        textSize = 17f
                        setPadding(dp(14), dp(6), dp(6), dp(6))
                        setOnClickListener { remove(activity, k); rebuild() }
                    })
                })
            }
            box.addView(TextView(activity).apply {
                text = "Новый символ"
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(6))
            })
            val nameField = field("Имя (латиница, без «\\»)")
            val valueField = field("Символ или текст")
            box.addView(nameField)
            box.addView(valueField)
            box.addView(TextView(activity).apply {
                text = "Добавить"
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { setColor(Th.bg(0xFF7E57C2.toInt())); cornerRadius = dp(16).toFloat() }
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener {
                    val n = nameField.text.toString().trim().removePrefix("\\")
                    val v = valueField.text.toString().trim()
                    if (!set(activity, n, v)) {
                        Toast.makeText(activity, Loc.t("Имя — только латинские буквы (до 32), символ не должен быть пустым"), Toast.LENGTH_LONG).show()
                    } else rebuild()
                }
            })
            Loc.translateTree(box)
        }
        rebuild()
        dialog = activity.styledPanelDialog("Математические символы", box, listOf(NanoBaseActivity.StyledDialogButton("Готово") {}))
    }
}
