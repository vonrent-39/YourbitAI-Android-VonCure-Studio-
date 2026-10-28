package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

class DevSettingsActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev_settings)
        findViewById<View>(R.id.btnBack).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowApiKeys).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowApiKeys).setOnClickListener {
            startActivity(Intent(this, ApiKeysActivity::class.java))
        }
        findViewById<View>(R.id.rowModels).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowModels).setOnClickListener {
            startActivity(Intent(this, ModelsActivity::class.java))
        }
        findViewById<View>(R.id.rowTerminal).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowTerminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
    }
}
