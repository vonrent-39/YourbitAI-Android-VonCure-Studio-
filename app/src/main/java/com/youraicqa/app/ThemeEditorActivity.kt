package com.youraicqa.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.youraicqa.app.linkimporter.MediaDownloadHelper
import org.json.JSONObject
import java.io.File

class ThemeEditorActivity : NanoBaseActivity() {

    companion object {
        const val PLUGIN_ID = "custom-theme-editor"
        private const val PREFS_KEY = "theme_editor_last_json"
    }

    private lateinit var colorFields: MutableMap<String, EditText>
    private lateinit var switches: MutableMap<String, Switch>
    private lateinit var seeks: MutableMap<String, SeekBar>
    private lateinit var seekLabels: MutableMap<String, TextView>
    private var bubbleEntrance: String = "none"
    private lateinit var entrancePills: MutableMap<String, TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_editor)
        findViewById<View>(R.id.btnBack).apply {
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        }

        colorFields = mutableMapOf()
        switches = mutableMapOf()
        seeks = mutableMapOf()
        seekLabels = mutableMapOf()
        entrancePills = mutableMapOf()

        val container = findViewById<LinearLayout>(R.id.editorContainer)
        val saved = loadSavedJson()

        container.addView(sectionTitle("Цвета"))
        container.addView(card(listOf(
            colorRow("background", "Фон экрана", "#101114", saved),
            switchRow("hideBackgroundImage", "Скрыть декоративную картинку", saved?.optBoolean("hideBackgroundImage", false) ?: false),
            colorRow("accent", "Акцент (кнопка отправки)", "#7C5CE0", saved),
            colorRow("cardBackground", "Фон карточек сообщений", "#1A1A1E", saved),
            colorRow("cardBorderColor", "Рамка карточек (пусто — без рамки)", "", saved),
            seekRow("cardBorderWidth", "Толщина рамки", 0, 8, (saved?.optDouble("cardBorderWidth", 0.0) ?: 0.0).toInt(), "dp"),
            seekRow("bubbleCornerRadius", "Скругление карточек", 0, 40, (saved?.optDouble("bubbleCornerRadius", 18.0) ?: 18.0).toInt(), "dp")
        )))

        container.addView(sectionTitle("Шапка и поле ввода"))
        container.addView(card(listOf(
            colorRow("headerBackground", "Фон шапки (пусто — прозрачный)", "", saved),
            colorRow("headerTextColor", "Текст/иконки шапки", "#FFFFFF", saved),
            switchRow("glassHeader", "Эффект «матового стекла» на шапке", saved?.optBoolean("glassHeader", false) ?: false),
            colorRow("inputBackground", "Фон поля ввода", "#1A1A1D", saved),
            colorRow("inputTextColor", "Текст поля ввода", "#FFFFFF", saved),
            switchRow("glassInput", "Эффект «матового стекла» на поле ввода", saved?.optBoolean("glassInput", false) ?: false)
        )))

        container.addView(sectionTitle("Градиент фона"))
        container.addView(card(listOf(
            colorRow(
                "gradientTo", "Второй цвет градиента (пусто — выключен)", "", saved,
                initialOverride = saved?.optJSONObject("backgroundGradient")?.optString("to")?.takeIf { it.isNotBlank() }
            ),
            seekRow("gradientAngle", "Угол градиента", 0, 360, (saved?.optJSONObject("backgroundGradient")?.optDouble("angle", 135.0) ?: 135.0).toInt(), "°")
        )))

        container.addView(sectionTitle("Эффекты"))
        container.addView(card(listOf(
            switchRow("accentGlow", "Свечение вокруг кнопки отправки", saved?.optBoolean("accentGlow", false) ?: false),
            entranceRow(saved?.optString("bubbleEntrance", "none") ?: "none")
        )))

        val applyBtn = TextView(this).apply {
            text = "Применить как тему"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_settings_card)
            setPadding(0, dp(14), 0, dp(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
            applyPressAnimation(PressAnimStyle.BOUNCE)
            setOnClickListener { applyTheme() }
        }
        container.addView(applyBtn)

        val exportBtn = TextView(this).apply {
            text = "Экспортировать как .plugin"
            setTextColor(Th.fg(0xFF8AB4F8.toInt()))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14.5f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_pill_grey)
            setPadding(0, dp(13), 0, dp(13))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            layoutParams = lp
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { exportTheme() }
        }
        container.addView(exportBtn)

        val resetBtn = TextView(this).apply {
            text = "Сбросить тему"
            setTextColor(Th.fg(0xFFFF6B5B.toInt()))
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14.5f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_destructive)
            setPadding(0, dp(13), 0, dp(13))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { resetTheme() }
        }
        container.addView(resetBtn)

        container.addView(TextView(this).apply {
            text = "Тема устанавливается как обычный плагин с разрешением «Оформление» — " +
                "её можно включить/выключить или удалить на экране «Плагины», как любую другую."
            setTextColor(Th.fg(0xFF666666.toInt()))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })
    }

    private fun buildColorsJson(): JSONObject {
        val o = JSONObject()
        fun putColor(key: String) {
            normalizeHex(colorFields[key]?.text?.toString())?.let { o.put(key, it) }
        }
        putColor("background")
        putColor("accent")
        putColor("cardBackground")
        putColor("cardBorderColor")
        putColor("headerBackground")
        putColor("headerTextColor")
        putColor("inputBackground")
        putColor("inputTextColor")

        switches["hideBackgroundImage"]?.let { o.put("hideBackgroundImage", it.isChecked) }
        switches["glassHeader"]?.let { o.put("glassHeader", it.isChecked) }
        switches["glassInput"]?.let { o.put("glassInput", it.isChecked) }
        switches["accentGlow"]?.let { o.put("accentGlow", it.isChecked) }

        seeks["cardBorderWidth"]?.let { o.put("cardBorderWidth", it.progress) }
        seeks["bubbleCornerRadius"]?.let { o.put("bubbleCornerRadius", it.progress) }

        if (o.has("cardBorderColor") && o.optInt("cardBorderWidth", 0) == 0) {
            o.put("cardBorderWidth", 1)
        }

        val gradientTo = normalizeHex(colorFields["gradientTo"]?.text?.toString())
        if (gradientTo != null) {
            val angle = seeks["gradientAngle"]?.progress ?: 135
            o.put("backgroundGradient", JSONObject().put("to", gradientTo).put("angle", angle))
        }

        if (bubbleEntrance != "none") o.put("bubbleEntrance", bubbleEntrance)
        return o
    }

    private fun applyTheme() {
        val colors = buildColorsJson()
        val script = "nano.setTheme(" + JSONObject().put("colors", colors).toString() + ");"
        val manifest = JSONObject()
            .put("id", PLUGIN_ID)
            .put("name", "Моя тема")
            .put("description", "Тема, собранная в расширенном редакторе тем")
            .put("author", "Вы")
            .put("version", "1.0")
            .put("permissions", org.json.JSONArray().put("UI_THEME"))
            .put("script", script)

        val scan = PluginScanner.scan(manifest.toString(), script)
        val plugin = InstalledPlugin(
            id = PLUGIN_ID,
            name = "Моя тема",
            description = "Тема, собранная в расширенном редакторе тем",
            author = "Вы",
            version = "1.0",
            permissions = setOf(PluginPermission.UI_THEME),
            capabilities = PluginCapabilities.NONE,
            script = script,
            sha256 = scan.sha256,
            enabled = true
        )
        PluginStore.install(this, plugin)
        PluginManager.reload(this)
        saveJson(colors)
        Toast.makeText(this, Loc.t("Тема применена"), Toast.LENGTH_SHORT).show()
    }

    private fun exportTheme() {
        try {
            val colors = buildColorsJson()
            val script = "nano.setTheme(" + JSONObject().put("colors", colors).toString() + ");"
            val manifest = JSONObject()
                .put("id", PLUGIN_ID)
                .put("name", "Моя тема")
                .put("description", "Тема, собранная в расширенном редакторе тем Yourbit AI")
                .put("author", "Вы")
                .put("version", "1.0")
                .put("permissions", org.json.JSONArray().put("UI_THEME"))
                .put("script", script)
            val file = File(cacheDir, "my-theme.plugin")
            file.writeText(manifest.toString(2), Charsets.UTF_8)
            val uri = MediaDownloadHelper.saveToDownloads(this, file)
            if (uri != null) {
                Toast.makeText(this, Loc.t("Сохранено в Загрузки: my-theme.plugin"), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, Loc.t("Не удалось сохранить файл"), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Экспорт: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    private fun resetTheme() {
        styledConfirmDialog(
            title = "Сбросить тему?",
            message = "Плагин «Моя тема» будет удалён, приложение вернётся к фирменному оформлению.",
            positiveText = "Сбросить",
            destructive = true
        ) {
            PluginStore.remove(this, PLUGIN_ID)
            PluginManager.reload(this)
            getSharedPreferences("nanoai", MODE_PRIVATE).edit().remove(PREFS_KEY).apply()
            recreate()
        }
    }

    private fun loadSavedJson(): JSONObject? {
        val raw = getSharedPreferences("nanoai", MODE_PRIVATE).getString(PREFS_KEY, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun saveJson(colors: JSONObject) {
        getSharedPreferences("nanoai", MODE_PRIVATE).edit().putString(PREFS_KEY, colors.toString()).apply()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun normalizeHex(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val withHash = if (s.startsWith("#")) s else "#$s"
        return try {
            Color.parseColor(withHash)
            withHash.uppercase()
        } catch (_: Exception) {
            null
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Th.fg(0xFFB39DFF.toInt()))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, dp(10), 0, dp(8))
    }

    private fun card(rows: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_settings_card)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(4)
        layoutParams = lp
        rows.forEach { addView(it) }
    }

    private fun colorRow(key: String, label: String, default: String, saved: JSONObject?, initialOverride: String? = null): View {
        val initial = initialOverride ?: (saved?.optString(key)?.takeIf { it.isNotBlank() } ?: default)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6).toFloat()
                setColor(try { Color.parseColor(if (initial.startsWith("#")) initial else "#$initial") } catch (_: Exception) { Th.bg(0xFF333333.toInt()) })
                setStroke(dp(1), Th.line(0xFF444444.toInt()))
            }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) }
            isClickable = true
            isFocusable = true
        }
        val field = EditText(this).apply {
            hint = "#RRGGBB"
            setText(initial)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            textSize = 13f
            background = getDrawable(R.drawable.bg_input_field)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val hex = normalizeHex(s?.toString()) ?: default.ifBlank { null }
                    val color = hex?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: 0xFF333333.toInt()
                    (swatch.background as? GradientDrawable)?.setColor(color)
                }
            })
        }
        swatch.applyPressAnimation(PressAnimStyle.LIFT)
        swatch.setOnClickListener {
            val current = normalizeHex(field.text?.toString())?.let {
                runCatching { Color.parseColor(it) }.getOrNull()
            } ?: 0xFF333333.toInt()
            openColorPickerDialog(current) { hex -> field.setText(hex) }
        }
        colorFields[key] = field
        val labelView = TextView(this).apply {
            text = label
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(labelView)
        row.addView(swatch)
        row.addView(field)
        return row
    }

    private fun switchRow(key: String, label: String, default: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 13.5f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = Switch(this).apply { isChecked = default }
        switches[key] = sw
        row.addView(sw)
        return row
    }

    private fun seekRow(key: String, label: String, min: Int, max: Int, default: Int, unit: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        val labelView = TextView(this).apply {
            text = "$label: ${default.coerceIn(min, max)}$unit"
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 13.5f
        }
        seekLabels[key] = labelView
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = (default - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    labelView.text = "$label: ${p + min}$unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        seeks[key] = seek
        col.addView(labelView)
        col.addView(seek)
        return col
    }

    private fun entranceRow(initial: String): View {
        bubbleEntrance = initial.ifBlank { "none" }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        col.addView(TextView(this).apply {
            text = "Анимация появления новых сообщений"
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 13.5f
            setPadding(0, 0, 0, dp(8))
        })
        val pillsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val options = listOf("none" to "Нет", "fade" to "Плавно", "scaleFade" to "Масштаб")
        options.forEach { (value, title) ->
            val pill = TextView(this).apply {
                text = title
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = if (value != options.last().first) dp(6) else 0
                layoutParams = lp
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener {
                    bubbleEntrance = value
                    entrancePills.forEach { (v, view) -> stylePill(view, v == bubbleEntrance) }
                }
            }
            entrancePills[value] = pill
            pillsRow.addView(pill)
        }
        entrancePills.forEach { (v, view) -> stylePill(view, v == bubbleEntrance) }
        col.addView(pillsRow)
        return col
    }

    private fun stylePill(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else Th.fg(0xFFAAAAAA.toInt()))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (selected) Th.bg(0xFF7C5CE0.toInt()) else Th.bg(0xFF232329.toInt()))
        }
    }

    private fun openColorPickerDialog(initialColor: Int, onPicked: (String) -> Unit) {
        fun hexOf(c: Int) = String.format("#%06X", 0xFFFFFF and c)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val wheelWrap = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Th.bg(0xFF26262E.toInt()), Th.bg(0xFF1A1A20.toInt()))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), Th.line(0x22FFFFFF))
            }
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(280)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6)
            }
        }
        val picker = ColorPickerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(262), dp(262)).apply { gravity = Gravity.CENTER }
            setColorSilently(initialColor)
        }
        wheelWrap.addView(picker)
        content.addView(wheelWrap)

        val preview = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(initialColor)
                setStroke(dp(1), Th.line(0xFF3A3A42.toInt()))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(18)
            }
        }
        content.addView(preview)

        val hexField = EditText(this).apply {
            setText(hexOf(initialColor))
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14.5f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_input_field)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }
        content.addView(hexField)

        var updating = false

        lateinit var rSeek: SeekBar
        lateinit var gSeek: SeekBar
        lateinit var bSeek: SeekBar
        lateinit var hSeek: SeekBar
        lateinit var sSeek: SeekBar
        lateinit var vSeek: SeekBar

        fun syncTo(color: Int) {
            updating = true
            picker.setColorSilently(color)
            (preview.background as? GradientDrawable)?.setColor(color)
            hexField.setText(hexOf(color))
            rSeek.progress = Color.red(color)
            gSeek.progress = Color.green(color)
            bSeek.progress = Color.blue(color)
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hSeek.progress = hsv[0].toInt()
            sSeek.progress = (hsv[1] * 100f).toInt()
            vSeek.progress = (hsv[2] * 100f).toInt()
            updating = false
        }

        fun channelRow(label: String, channelColor: Int, max: Int, initial: Int, suffix: String = "", onUserChange: (Int) -> Unit): Pair<LinearLayout, SeekBar> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(channelColor)
                setTypeface(typeface, Typeface.BOLD)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val valueTv = TextView(this).apply {
                text = "$initial$suffix"
                setTextColor(Th.fg(0xFFCCCCCC.toInt()))
                textSize = 12.5f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val seek = SeekBar(this).apply {
                this.max = max
                progress = initial
                progressTintList = android.content.res.ColorStateList.valueOf(channelColor)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Th.bg(0xFF3A3A42.toInt()))
                progressBackgroundTintMode = android.graphics.PorterDuff.Mode.SRC_IN
                thumb = getDrawable(R.drawable.seek_thumb_glass)
                thumbOffset = 0
                splitTrack = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10); marginEnd = dp(10)
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        valueTv.text = "$p$suffix"
                        if (fromUser) onUserChange(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(seek)
            row.addView(valueTv)
            return row to seek
        }

        val channelRed = 0xFFFF5C5C.toInt()
        val channelGreen = 0xFF5CE07C.toInt()
        val channelBlue = 0xFF5C9CFF.toInt()

        val rgbContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val (rRow, rSeekBar) = channelRow("R", channelRed, 255, Color.red(initialColor)) { v ->
            if (!updating) syncTo(Color.rgb(v, Color.green(picker.currentColor()), Color.blue(picker.currentColor())))
        }
        val (gRow, gSeekBar) = channelRow("G", channelGreen, 255, Color.green(initialColor)) { v ->
            if (!updating) syncTo(Color.rgb(Color.red(picker.currentColor()), v, Color.blue(picker.currentColor())))
        }
        val (bRow, bSeekBar) = channelRow("B", channelBlue, 255, Color.blue(initialColor)) { v ->
            if (!updating) syncTo(Color.rgb(Color.red(picker.currentColor()), Color.green(picker.currentColor()), v))
        }
        rSeek = rSeekBar; gSeek = gSeekBar; bSeek = bSeekBar
        rgbContainer.addView(rRow); rgbContainer.addView(gRow); rgbContainer.addView(bRow)
        content.addView(rgbContainer)

        val hsbContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fun currentHsv(): FloatArray { val hsv = FloatArray(3); Color.colorToHSV(picker.currentColor(), hsv); return hsv }
        val (hRow, hSeekBar) = channelRow("H", 0xFFDDA0FF.toInt(), 360, currentHsv()[0].toInt(), "°") { v ->
            if (!updating) { val hsv = currentHsv(); hsv[0] = v.toFloat(); syncTo(Color.HSVToColor(hsv)) }
        }
        val (sRow, sSeekBar) = channelRow("S", 0xFF5CE0C7.toInt(), 100, (currentHsv()[1] * 100).toInt(), "%") { v ->
            if (!updating) { val hsv = currentHsv(); hsv[1] = v / 100f; syncTo(Color.HSVToColor(hsv)) }
        }
        val (vRow, vSeekBar) = channelRow("B", 0xFFE0D65C.toInt(), 100, (currentHsv()[2] * 100).toInt(), "%") { v ->
            if (!updating) { val hsv = currentHsv(); hsv[2] = v / 100f; syncTo(Color.HSVToColor(hsv)) }
        }
        hSeek = hSeekBar; sSeek = sSeekBar; vSeek = vSeekBar
        hsbContainer.addView(hRow); hsbContainer.addView(sRow); hsbContainer.addView(vRow)
        content.addView(hsbContainer)

        val panelColors = listOf(
            0xFF000000, 0xFFFFFFFF, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759, 0xFF00C7BE, 0xFF30ADE6,
            0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D78, 0xFF8AB4F8, 0xFF7C5CE0, 0xFFB39DFF, 0xFF5CE07C,
            0xFF5C9CFF, 0xFFE0A458, 0xFF888888, 0xFF3A3A42
        ).map { it.toInt() }
        val panelGrid = android.widget.GridLayout(this).apply {
            columnCount = 5
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        panelColors.forEach { c ->
            panelGrid.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(2), 0x66FFFFFF)
                }
                val lp = android.widget.GridLayout.LayoutParams()
                lp.width = dp(44); lp.height = dp(44)
                lp.setMargins(dp(6), dp(6), dp(6), dp(6))
                layoutParams = lp
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener { syncTo(c) }
            })
        }
        val panelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            gravity = Gravity.CENTER_HORIZONTAL
            addView(panelGrid)
        }
        content.addView(panelContainer)

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        }
        fun tabButton(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
            applyPressAnimation(PressAnimStyle.SOFT)
        }
        val tabPanel = tabButton("Панель")
        val tabRgb = tabButton("RGB")
        val tabHsb = tabButton("HSB")
        val tabs = listOf(tabPanel to panelContainer, tabRgb to rgbContainer, tabHsb to hsbContainer)
        fun selectTab(selected: TextView) {
            tabs.forEach { (btn, view) ->
                val isSel = btn === selected
                view.visibility = if (isSel) View.VISIBLE else View.GONE
                btn.setTextColor(if (isSel) Th.fg(0xFFFFFFFF.toInt()) else Th.fg(0xFF8A8A92.toInt()))
                btn.background = if (isSel) GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Th.bg(0xFF3A3A42.toInt()))
                } else null
            }
        }
        tabPanel.setOnClickListener { selectTab(tabPanel) }
        tabRgb.setOnClickListener { selectTab(tabRgb) }
        tabHsb.setOnClickListener { selectTab(tabHsb) }
        tabRow.addView(tabPanel); tabRow.addView(tabRgb); tabRow.addView(tabHsb)
        content.addView(tabRow, content.indexOfChild(rgbContainer))
        selectTab(tabRgb)

        picker.onColorChanged = { color -> if (!updating) syncTo(color) }

        hexField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val hex = normalizeHex(s?.toString()) ?: return
                val color = runCatching { Color.parseColor(hex) }.getOrNull() ?: return
                syncTo(color)
            }
        })

        styledPanelDialog(
            title = "Цвет",
            contentView = ScrollView(this).apply { addView(content) },
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Применить") { onPicked(hexOf(picker.currentColor())) }
            )
        )
    }
}
