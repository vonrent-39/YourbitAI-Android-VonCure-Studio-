package com.youraicqa.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator

class WelcomeActivity : NanoBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        val btnStart = findViewById<View>(R.id.btnStart)
        btnStart.applyPressAnimation(PressAnimStyle.BOUNCE)
        btnStart.setOnClickListener {
            getSharedPreferences("nanoai", MODE_PRIVATE)
                .edit().putBoolean("welcome_seen", true).apply()
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
            finish()
        }

        playEntranceAnimation()
    }

    private fun playEntranceAnimation() {
        val content = findViewById<android.view.ViewGroup>(R.id.welcomeContent)
        var order = 0
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v.layoutParams is android.widget.LinearLayout.LayoutParams && (v.layoutParams as android.widget.LinearLayout.LayoutParams).weight > 0f) continue
            v.alpha = 0f
            v.translationY = 36f
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(80L * order++).setDuration(420)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        findViewById<View>(R.id.welcomeLogo)?.let { logo ->
            if (UiPrefs.animationsEnabled(this)) {
                android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    logo,
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f, 1f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f, 1f)
                ).apply { duration = 2400; repeatCount = android.animation.ObjectAnimator.INFINITE; startDelay = 600; start() }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }
}
