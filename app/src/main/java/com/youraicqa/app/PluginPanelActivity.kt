package com.youraicqa.app

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView

class PluginPanelActivity : NanoBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("plugin_id") ?: ""
        val extId = intent.getStringExtra("ext_id")
        val extPanel = if (extId.isNullOrBlank()) null else
            PluginManager.activeUiExtensions(this, UiExtension.SLOT_SETTINGS_TAB).firstOrNull { it.pluginId == id && it.id == extId }?.panel
        val html = extPanel?.takeIf { it.isNotBlank() } ?: PluginManager.activePanels(this)[id]

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF101114.toInt()))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }
        bar.addView(TextView(this).apply {
            text = "‹ Назад"
            setTextColor(Th.fg(0xFF8AB4F8.toInt()))
            textSize = 15f
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        root.addView(bar)

        if (html.isNullOrBlank()) {
            root.addView(TextView(this).apply {
                text = "Плагин не предоставил панель."
                setTextColor(Th.fg(0xFF888888.toInt()))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(40), dp(24), dp(24))
            })
        } else {
            val wv = WebView(this)
            wv.settings.javaScriptEnabled = false
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
            root.addView(wv, LinearLayout.LayoutParams(-1, -1))
        }

        setContentView(root)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
