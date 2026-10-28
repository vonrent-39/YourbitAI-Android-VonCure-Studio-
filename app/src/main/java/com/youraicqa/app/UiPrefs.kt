package com.youraicqa.app

import android.content.Context
import android.graphics.Typeface
import java.io.File

object UiPrefs {
    private const val PREFS = "nanoai"
    private const val KEY_FONT_SCALE = "ui_font_scale"
    private const val KEY_ANIM = "ui_anim"
    private const val KEY_COMPACT = "ui_compact"
    private const val KEY_FONT_PATH = "ui_custom_font_path"
    private const val KEY_FONT_NAME = "ui_custom_font_name"
    private const val KEY_PERSONA = "ui_custom_persona"
    private const val KEY_USER_BIO = "ui_user_bio"
    private const val KEY_LEGACY_BG = "ui_legacy_gradient_bg"
    private const val KEY_THEME_DARK = "ui_theme_dark"
    private const val KEY_SIMPLE = "ui_simple"
    private const val KEY_JUMP_BTNS = "ui_scroll_jump_buttons"

    private var cachedTypeface: Typeface? = null
    private var cachedPath: String? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** null = follow system, true = force dark, false = force light. */
    fun themeOverride(context: Context): Boolean? {
        val p = prefs(context)
        return if (!p.contains(KEY_THEME_DARK)) null else p.getBoolean(KEY_THEME_DARK, false)
    }

    fun setThemeOverride(context: Context, dark: Boolean?) {
        val e = prefs(context).edit()
        if (dark == null) e.remove(KEY_THEME_DARK) else e.putBoolean(KEY_THEME_DARK, dark)
        e.apply()
    }

    fun applyThemeContext(base: Context): Context {
        val override = themeOverride(base) ?: return base
        val config = android.content.res.Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (override) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
        return base.createConfigurationContext(config)
    }
    fun fontScale(context: Context): Float = prefs(context).getFloat(KEY_FONT_SCALE, 1f)

    fun setFontScale(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_FONT_SCALE, v).apply()
    }

    fun animationsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ANIM, true)

    fun setAnimationsEnabled(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANIM, v).apply()
    }

    fun compactMode(context: Context): Boolean = prefs(context).getBoolean(KEY_COMPACT, false)

    fun setCompactMode(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPACT, v).apply()
    }

    /** Minimal chat UI: no empty-state shortcuts, fewer message actions, no blur/glass or jump buttons. */
    fun simpleUi(context: Context): Boolean = prefs(context).getBoolean(KEY_SIMPLE, false)

    fun setSimpleUi(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIMPLE, v).apply()
    }

    fun scrollJumpButtons(context: Context): Boolean = prefs(context).getBoolean(KEY_JUMP_BTNS, true)

    fun setScrollJumpButtons(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_JUMP_BTNS, v).apply()
    }

    fun legacyGradientBackground(context: Context): Boolean = prefs(context).getBoolean(KEY_LEGACY_BG, false)

    fun setLegacyGradientBackground(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEGACY_BG, v).apply()
    }

    fun customPersona(context: Context): String = prefs(context).getString(KEY_PERSONA, "") ?: ""

    fun setCustomPersona(context: Context, text: String) {
        prefs(context).edit().putString(KEY_PERSONA, text).apply()
    }

    fun userBio(context: Context): String = prefs(context).getString(KEY_USER_BIO, "") ?: ""

    fun setUserBio(context: Context, text: String) {
        prefs(context).edit().putString(KEY_USER_BIO, text).apply()
    }

    fun customFontName(context: Context): String? = prefs(context).getString(KEY_FONT_NAME, null)

    fun customFontPath(context: Context): String? = prefs(context).getString(KEY_FONT_PATH, null)

    fun fontsDir(context: Context): File =
        File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    fun saveCustomFont(context: Context, path: String, displayName: String) {
        prefs(context).edit()
            .putString(KEY_FONT_PATH, path)
            .putString(KEY_FONT_NAME, displayName)
            .apply()
        cachedTypeface = null
        cachedPath = null
    }

    fun resetCustomFont(context: Context) {
        customFontPath(context)?.let { runCatching { File(it).delete() } }
        prefs(context).edit().remove(KEY_FONT_PATH).remove(KEY_FONT_NAME).apply()
        cachedTypeface = null
        cachedPath = null
    }

    fun customTypeface(context: Context): Typeface? {
        val path = customFontPath(context) ?: return null
        if (cachedTypeface != null && cachedPath == path) return cachedTypeface
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val tf = Typeface.createFromFile(file)
            cachedTypeface = tf
            cachedPath = path
            tf
        } catch (_: Exception) {
            null
        }
    }

    fun applyGlobalFontIfNeeded(context: Context) {
        val tf = customTypeface(context) ?: return
        try {
            val boldTf = Typeface.create(tf, Typeface.BOLD)
            listOf("DEFAULT" to tf, "DEFAULT_BOLD" to boldTf).forEach { (fieldName, value) ->
                val field = Typeface::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(null, value)
            }
        } catch (_: Exception) {
        }
    }
}
