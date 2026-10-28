package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Lives in the ":restart" process (see AndroidManifest). Waits until the main app process is gone,
 * then starts the requested screen, which makes Android create a clean new app process.
 */
class RestartActivity : Activity() {
    companion object { const val EXTRA_NEXT = "restart_next_intent" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val next = intent?.getParcelableExtra<Intent>(EXTRA_NEXT)
        Handler(Looper.getMainLooper()).postDelayed({
            try { if (next != null) startActivity(next) } catch (_: Exception) {}
            finish()
            Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 500)
        }, 900)
    }
}
