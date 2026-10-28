package com.youraicqa.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView

class ModesActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modes)
        findViewById<View>(R.id.btnBack).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowArena).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowArena).setOnClickListener {
            startActivity(Intent(this, ArenaActivity::class.java))
        }
        findViewById<View>(R.id.rowAgent).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowAgent).setOnClickListener {
            startActivity(Intent(this, AgentActivity::class.java))
        }
        findViewById<View>(R.id.rowCoder).applyPressAnimation(PressAnimStyle.LIFT)
        findViewById<View>(R.id.rowCoder).setOnClickListener {
            styledPanelDialog(
                "Yourbit Coder",
                TextView(this).apply {
                    text = "Скоро будет"
                    textSize = 15f
                    setTextColor(Th.fg(0xFFAAAAAA.toInt()))
                },
                listOf(StyledDialogButton("ОК") {})
            )
        }
    }
}
