package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

class CustomizeActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true

    private val pickFontReq = 501
    private val pickAvatarUserReq = 502
    private val pickAvatarAssistantReq = 503
    private lateinit var fontFileLabel: TextView
    private var pendingAvatarPreview: android.widget.ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiPrefs.applyGlobalFontIfNeeded(this)
        setContentView(R.layout.activity_customize)
        AchievementsStore.onTabOpened(this, "customize")

        findViewById<View>(R.id.btnBack).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        run {
            val tint = Th.fg(0xFFFFFFFF.toInt())
            Ico.start(findViewById<android.widget.TextView>(R.id.btnEditUserProfile), R.drawable.vi_user, 20f, tint, 12f)
            Ico.start(findViewById<android.widget.TextView>(R.id.btnEditAssistantProfile), R.drawable.vi_robot, 20f, tint, 12f)
            Ico.start(findViewById<android.widget.TextView>(R.id.btnThemeEditor), R.drawable.vi_palette, 20f, tint, 12f)
        }
        findViewById<View>(R.id.btnThemeEditor).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnThemeEditor).setOnClickListener {
            startActivity(Intent(this, ThemeEditorActivity::class.java))
        }

        findViewById<View>(R.id.btnEditUserProfile).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnEditUserProfile).setOnClickListener { openProfileEditor(isUser = true) }
        findViewById<View>(R.id.btnEditAssistantProfile).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnEditAssistantProfile).setOnClickListener { openProfileEditor(isUser = false) }

        val fontScale = findViewById<SeekBar>(R.id.seekFont)
        val fontLabel = findViewById<TextView>(R.id.fontLabel)
        val animSwitch = findViewById<Switch>(R.id.switchAnim)
        val compactSwitch = findViewById<Switch>(R.id.switchCompact)
        val legacyBgSwitch = findViewById<Switch>(R.id.switchLegacyBg)
        val simpleSwitch = findViewById<Switch>(R.id.switchSimple)
        val jumpSwitch = findViewById<Switch>(R.id.switchJump)
        val themeSwitch = findViewById<Switch>(R.id.switchTheme)
        val themeModeLabel = findViewById<TextView>(R.id.themeModeLabel)
        val themeAuto = findViewById<TextView>(R.id.btnThemeAuto)
        styleSwitch(animSwitch)
        styleSwitch(compactSwitch)
        styleSwitch(legacyBgSwitch)
        styleSwitch(simpleSwitch)
        styleSwitch(jumpSwitch)
        styleSwitch(themeSwitch)
        fontFileLabel = findViewById(R.id.fontFileLabel)
        val btnPickFont = findViewById<View>(R.id.btnPickFont)
        val btnResetFont = findViewById<View>(R.id.btnResetFont)

        val scale = UiPrefs.fontScale(this)
        fontScale.progress = ((scale - 0.85f) / 0.015f).toInt().coerceIn(0, 20)
        fontLabel.text = "Размер текста: ${(scale * 100).toInt()}%"
        animSwitch.isChecked = UiPrefs.animationsEnabled(this)
        compactSwitch.isChecked = UiPrefs.compactMode(this)
        legacyBgSwitch.isChecked = UiPrefs.legacyGradientBackground(this)
        simpleSwitch.isChecked = UiPrefs.simpleUi(this)
        jumpSwitch.isChecked = UiPrefs.scrollJumpButtons(this)
        val themeOverride = UiPrefs.themeOverride(this)
        themeSwitch.isChecked = themeOverride ?: isSystemNight()
        themeModeLabel.text = when (themeOverride) {
            null -> "Авто: как в системе"
            true -> "Вручную: тёмная"
            false -> "Вручную: светлая"
        }
        updateFontFileLabel()

        fontScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 0.85f + p * 0.015f
                fontLabel.text = "Размер текста: ${(v * 100).toInt()}%"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                val v = 0.85f + (s?.progress ?: 10) * 0.015f
                UiPrefs.setFontScale(this@CustomizeActivity, v)
            }
        })
        animSwitch.setOnCheckedChangeListener { _, c ->
            UiPrefs.setAnimationsEnabled(this, c)
        }
        compactSwitch.setOnCheckedChangeListener { _, c ->
            UiPrefs.setCompactMode(this, c)
        }
        legacyBgSwitch.setOnCheckedChangeListener { _, c ->
            UiPrefs.setLegacyGradientBackground(this, c)
        }
        simpleSwitch.setOnCheckedChangeListener { _, c -> UiPrefs.setSimpleUi(this, c) }
        jumpSwitch.setOnCheckedChangeListener { _, c -> UiPrefs.setScrollJumpButtons(this, c) }
        themeSwitch.setOnCheckedChangeListener { _, c ->
            UiPrefs.setThemeOverride(this, c)
            themeModeLabel.text = if (c) "Вручную: тёмная" else "Вручную: светлая"
            recreate()
        }
        themeAuto.applyPressAnimation(PressAnimStyle.SOFT)
        themeAuto.setOnClickListener {
            UiPrefs.setThemeOverride(this, null)
            recreate()
        }

        btnPickFont.applyPressAnimation(PressAnimStyle.SOFT)
        btnPickFont.setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "font/ttf", "font/otf", "font/collection",
                    "application/x-font-ttf", "application/x-font-otf",
                    "application/octet-stream"
                ))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, "Выбрать файл шрифта"), pickFontReq)
        }

        btnResetFont.applyPressAnimation(PressAnimStyle.SOFT)
        btnResetFont.setOnClickListener {
            UiPrefs.resetCustomFont(this)
            updateFontFileLabel()
            Toast.makeText(this, Loc.t("Шрифт сброшен. Перезапустите приложение, чтобы применить."), Toast.LENGTH_LONG).show()
        }

        val personaField = findViewById<EditText>(R.id.personaField)
        personaField.setText(UiPrefs.customPersona(this))
        findViewById<View>(R.id.btnSavePersona).applyPressAnimation(PressAnimStyle.BOUNCE)
        findViewById<View>(R.id.btnSavePersona).setOnClickListener {
            UiPrefs.setCustomPersona(this, personaField.text.toString())
            Toast.makeText(this, Loc.t("Характер сохранён"), Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnResetPersona).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnResetPersona).setOnClickListener {
            if (personaField.text.isNullOrBlank()) {
                UiPrefs.setCustomPersona(this, "")
                return@setOnClickListener
            }
            styledConfirmDialog(
                title = "Сбросить характер?",
                message = "Написанные инструкции характера будут удалены без возможности восстановить.",
                positiveText = "Сбросить",
                destructive = true
            ) {
                UiPrefs.setCustomPersona(this, "")
                personaField.setText("")
                Toast.makeText(this, Loc.t("Инструкции характера сброшены"), Toast.LENGTH_SHORT).show()
            }
        }

        val userBioField = findViewById<EditText>(R.id.userBioField)
        userBioField.setText(UiPrefs.userBio(this))
        findViewById<View>(R.id.btnSaveUserBio).applyPressAnimation(PressAnimStyle.BOUNCE)
        findViewById<View>(R.id.btnSaveUserBio).setOnClickListener {
            UiPrefs.setUserBio(this, userBioField.text.toString())
            Toast.makeText(this, Loc.t("Информация о себе сохранена"), Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnResetUserBio).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnResetUserBio).setOnClickListener {
            if (userBioField.text.isNullOrBlank()) {
                UiPrefs.setUserBio(this, "")
                return@setOnClickListener
            }
            styledConfirmDialog(
                title = "Сбросить «О себе»?",
                message = "Написанная информация о себе будет удалена без возможности восстановить.",
                positiveText = "Сбросить",
                destructive = true
            ) {
                UiPrefs.setUserBio(this, "")
                userBioField.setText("")
                Toast.makeText(this, Loc.t("Информация о себе сброшена"), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnReset).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnReset).setOnClickListener {
            styledConfirmDialog(
                title = "Сбросить оформление?",
                message = "Размер текста, анимации, компактный режим, инструкции характера и " +
                    "информация о себе вернутся к значениям по умолчанию.",
                positiveText = "Сбросить",
                destructive = true
            ) {
                UiPrefs.setFontScale(this, 1f)
                UiPrefs.setAnimationsEnabled(this, true)
                UiPrefs.setCompactMode(this, false)
                UiPrefs.setSimpleUi(this, false)
                UiPrefs.setScrollJumpButtons(this, true)
                simpleSwitch.isChecked = false
                jumpSwitch.isChecked = true
                UiPrefs.setCustomPersona(this, "")
                UiPrefs.setUserBio(this, "")
                personaField.setText("")
                userBioField.setText("")
                fontScale.progress = 10
                fontLabel.text = "Размер текста: 100%"
                animSwitch.isChecked = true
                compactSwitch.isChecked = false
                Toast.makeText(this, Loc.t("Сброшено"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickAvatarUserReq || requestCode == pickAvatarAssistantReq) {
            val uri: Uri = data?.data ?: return
            val isUser = requestCode == pickAvatarUserReq
            val ok = ProfileStore.saveAvatarFromUri(this, isUser, uri)
            if (ok) {
                pendingAvatarPreview?.let { refreshAvatarPreview(it, isUser) }
                Toast.makeText(this, Loc.t("Аватар обновлён"), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, Loc.t("Не удалось загрузить изображение"), Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (requestCode != pickFontReq || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val name = queryFileName(uri) ?: "custom_font"
            val ext = when {
                name.endsWith(".otf", true) -> "otf"
                name.endsWith(".ttf", true) -> "ttf"
                name.endsWith(".ttc", true) -> "ttc"
                else -> "ttf"
            }
            val destFile = File(UiPrefs.fontsDir(this), "custom_font.$ext")
            contentResolver.openInputStream(uri)?.use { ins ->
                destFile.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: run {
                Toast.makeText(this, Loc.t("Не удалось прочитать файл"), Toast.LENGTH_SHORT).show()
                return
            }
            android.graphics.Typeface.createFromFile(destFile)
            UiPrefs.saveCustomFont(this, destFile.absolutePath, name)
            UiPrefs.applyGlobalFontIfNeeded(this)
            updateFontFileLabel()
            Toast.makeText(this, Loc.t("Шрифт загружен. Перезапустите приложение, чтобы применить везде."), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Не удалось загрузить шрифт: похоже, файл повреждён или это не .ttf/.otf"), Toast.LENGTH_LONG).show()
        }
    }

    private fun openProfileEditor(isUser: Boolean) {
        val avatarPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
            }
            clipToOutline = true
            refreshAvatarPreview(this, isUser)
            isClickable = true
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                pendingAvatarPreview = this
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(
                    Intent.createChooser(i, "Выбрать аватар"),
                    if (isUser) pickAvatarUserReq else pickAvatarAssistantReq
                )
            }
        }
        val hint = TextView(this).apply {
            text = "Нажмите на аватар, чтобы выбрать изображение"
            setTextColor(Th.fg(0xFF999999.toInt()))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        val nameField = EditText(this).apply {
            setText(if (isUser) ProfileStore.userName(this@CustomizeActivity) else ProfileStore.assistantName(this@CustomizeActivity))
            setHint(if (isUser) ProfileStore.DEFAULT_USER_NAME else ProfileStore.DEFAULT_ASSISTANT_NAME)
            setHintTextColor(Th.fg(0xFF777777.toInt()))
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF1E1E26.toInt()))
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setSelection(text.length)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(avatarPreview)
            addView(hint)
            addView(nameField)
        }
        styledPanelDialog(
            title = if (isUser) "Мой профиль" else "Профиль YourAI",
            contentView = content,
            buttons = listOf(
                StyledDialogButton("Сбросить аватар", keepOpen = true) {
                    ProfileStore.resetAvatar(this, isUser)
                    refreshAvatarPreview(avatarPreview, isUser)
                },
                StyledDialogButton("Сохранить") {
                    if (isUser) ProfileStore.setUserName(this, nameField.text?.toString().orEmpty())
                    else ProfileStore.setAssistantName(this, nameField.text?.toString().orEmpty())
                    Toast.makeText(this, Loc.t("Профиль сохранён"), Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    private fun cropToCircle(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val size = minOf(src.width, src.height)
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        val output = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        val matrix = android.graphics.Matrix().apply { setTranslate(-x.toFloat(), -y.toFloat()) }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        val radius = size / 2f
        canvas.drawCircle(radius, radius, radius, paint)
        return output
    }

    private fun refreshAvatarPreview(iv: ImageView, isUser: Boolean) {
        val bmp = ProfileStore.avatarBitmap(this, isUser)
        if (bmp != null) {
            iv.setColorFilter(null)
            iv.setImageBitmap(cropToCircle(bmp))
            iv.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
            }
        } else {
            if (isUser) {
                iv.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.vi_user))
                iv.setColorFilter(0xFFFFFFFF.toInt())
                iv.background = ContextCompat.getDrawable(this, R.drawable.bg_avatar_circle)
                iv.setPadding(dp(20), dp(20), dp(20), dp(20))
            } else {
                iv.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_ai_logo))
                iv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                iv.setColorFilter(0xFFFFFFFF.toInt())
                iv.background = ContextCompat.getDrawable(this, R.drawable.bg_avatar_bot)
                iv.setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        }
    }

    private fun styleSwitch(sw: Switch) {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        sw.thumbTintList = ColorStateList(states, intArrayOf(Th.fg(0xFFB39DFF.toInt()), Th.fg(0xFFB5B5BD.toInt())))
        sw.trackTintList = ColorStateList(states, intArrayOf(Th.bg(0x99B39DFF.toInt()), Th.bg(0x66FFFFFF.toInt())))
    }

    private fun isSystemNight(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun updateFontFileLabel() {
        val name = UiPrefs.customFontName(this)
        fontFileLabel.text = name ?: "Системный шрифт"
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
