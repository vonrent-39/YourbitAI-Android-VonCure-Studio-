package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.rowDevSettings).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowDevSettings).setOnClickListener {
            startActivity(Intent(this, DevSettingsActivity::class.java))
        }
        findViewById<View>(R.id.rowPlugins).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowPlugins).setOnClickListener {
            startActivity(Intent(this, PluginsActivity::class.java))
        }
        findViewById<View>(R.id.rowAdvanced).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowAdvanced).setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }
        findViewById<View>(R.id.rowFaq).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowFaq).setOnClickListener {
            startActivity(Intent(this, FaqActivity::class.java).putExtra("from_settings", true))
        }
        findViewById<View>(R.id.rowCustomize).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowCustomize).setOnClickListener {
            startActivity(Intent(this, CustomizeActivity::class.java))
        }
        findViewById<View>(R.id.rowEconomy).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowEconomy).setOnClickListener {
            startActivity(Intent(this, EconomyActivity::class.java))
        }
        findViewById<View>(R.id.rowStats).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        findViewById<View>(R.id.rowAuthority).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowAuthority).setOnClickListener {
            startActivity(Intent(this, ModelAuthorityActivity::class.java))
        }
        findViewById<View>(R.id.rowLanguage).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowLanguage).setOnClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }
        findViewById<View>(R.id.rowModes).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowModes).setOnClickListener {
            startActivity(Intent(this, ModesActivity::class.java))
        }
        findViewById<View>(R.id.rowMathSymbols).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowMathSymbols).setOnClickListener { CustomMathSymbols.showEditor(this) }
        findViewById<View>(R.id.rowBackup).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowBackup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }
        findViewById<View>(R.id.rowAbout).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.rowBack).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowBack).setOnClickListener { finish() }
        addPluginTabs()
    }

    override fun onResume() {
        super.onResume()
        addPluginTabs()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addPluginTabs() {
        val anchor = findViewById<View>(R.id.rowBackup) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        for (i in parent.childCount - 1 downTo 0) {
            if (parent.getChildAt(i).getTag(R.id.tagPluginExtRow) == true) parent.removeViewAt(i)
        }
        val exts = try { PluginManager.activeUiExtensions(this, UiExtension.SLOT_SETTINGS_TAB) } catch (_: Exception) { emptyList() }
        var at = parent.indexOfChild(anchor)
        for (ext in exts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_settings_card)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(12) }
                setTag(R.id.tagPluginExtRow, true)
                isClickable = true
            }
            val iconRes = UiExtension.iconRes(ext.icon)
            val tile: View = if (iconRes != null) ImageView(this).apply {
                setImageResource(iconRes)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            } else TextView(this).apply {
                text = ext.icon.ifBlank { ext.title.take(1).uppercase() }
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            }
            tile.setBackgroundResource(R.drawable.bg_settings_icon)
            row.addView(tile, LinearLayout.LayoutParams(dp(64), dp(64)))
            row.addView(TextView(this).apply {
                text = ext.title
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 17f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            row.addView(TextView(this).apply {
                text = "›"; textSize = 22f; setTextColor(Th.fg(0xFF8A8A92.toInt()))
            })
            row.applyPressAnimation(PressAnimStyle.LIFT)
            row.setOnClickListener {
                if (ext.panel.isNotBlank()) {
                    startActivity(Intent(this, PluginPanelActivity::class.java)
                        .putExtra("plugin_id", ext.pluginId).putExtra("ext_id", ext.id))
                } else {
                    PluginManager.invokeUiAction(this, ext.pluginId, ext.id, org.json.JSONObject().put("slot", ext.slot))
                }
            }
            parent.addView(row, at++)
        }
    }
}
