package com.youraicqa.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SplashActivity : NanoBaseActivity() {
    private lateinit var orb: SplashOrbView
    private lateinit var splashTitle: TextView
    private lateinit var btnOffline: TextView
    private lateinit var progressPct: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var attempt = 1
    private var finished = false
    private var progress = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        orb = findViewById(R.id.splashOrb)
        splashTitle = findViewById(R.id.splashTitle)
        btnOffline = findViewById(R.id.btnOffline)
        progressPct = findViewById(R.id.progressPct)
        statusTitle = findViewById(R.id.statusTitle)
        statusSub = findViewById(R.id.statusSub)

        orb.setAnimated(EconomyPrefs.pulseEnabled(this))
        btnOffline.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Th.line(0xFFB39DFF.toInt()))
            setColor(0x14FFFFFF)
        }
        btnOffline.setOnClickListener { goNext() }
        playIntro()

        val crash = CrashReporter.take(this)
        if (crash == null) {
            startLoadingPipeline()
        } else {
            showCrashReport(crash)
        }
    }

    private fun playIntro() {
        orb.alpha = 0f; orb.scaleX = 0.7f; orb.scaleY = 0.7f
        orb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
        splashTitle.alpha = 0f
        splashTitle.translationY = dp(14).toFloat()
        splashTitle.letterSpacing = 0.3f
        splashTitle.animate().alpha(1f).translationY(0f).setStartDelay(250).setDuration(600).start()
        android.animation.ValueAnimator.ofFloat(0.3f, 0.01f).apply {
            startDelay = 250
            duration = 800
            addUpdateListener { splashTitle.letterSpacing = it.animatedValue as Float }
            start()
        }
        listOf(statusTitle, statusSub, progressPct).forEachIndexed { i, v ->
            v.alpha = 0f
            v.animate().alpha(1f).setStartDelay(500L + i * 120L).setDuration(450).start()
        }
    }

    private var pipelineStarted = false

    private fun showCrashReport(report: String) {
        val tv = TextView(this).apply {
            text = report.take(8000)
            setTextColor(Th.fg(0xFFE5E5EA.toInt()))
            textSize = 11f
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(tv)
            setPadding(0, dp(4), 0, 0)
        }
        val dialog = styledPanelDialog(
            title = "Приложение упало в прошлый раз",
            contentView = scroll,
            buttons = listOf(
                StyledDialogButton("Копировать", keepOpen = true) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", report))
                    android.widget.Toast.makeText(this, "Скопировано", android.widget.Toast.LENGTH_SHORT).show()
                },
                StyledDialogButton("Продолжить") {}
            )
        )
        dialog.setOnDismissListener { continueAfterCrashReport() }
    }

    private fun continueAfterCrashReport() {
        if (pipelineStarted) return
        pipelineStarted = true
        startLoadingPipeline()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun playSuccessCue(then: () -> Unit) {
        btnOffline.visibility = View.INVISIBLE
        orb.playSuccess(then)
    }

    private fun playFailCue() {
        orb.playFail()
        if (btnOffline.visibility != View.VISIBLE) {
            btnOffline.alpha = 0f
            btnOffline.visibility = View.VISIBLE
            btnOffline.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun setStatusText(title: String?, sub: String?) {
        if (title != null && statusTitle.text.toString() != title) statusTitle.text = title
        if (sub != null && statusSub.text.toString() != sub) {
            statusSub.animate().alpha(0f).setDuration(110).withEndAction {
                statusSub.text = sub
                statusSub.animate().alpha(1f).setDuration(180).start()
            }.start()
        }
    }

    private fun setProgressUi(p: Int, title: String? = null, sub: String? = null) {
        progress = p.coerceIn(0, 100)
        orb.setProgress(progress / 100f)
        progressPct.text = "$progress%"
        setStatusText(title, sub)
    }

    private fun startLoadingPipeline() {
        io.execute {
            var ok = false
            try {
                handler.post { setProgressUi(8, "Загрузка...", "Проверка сети") }
                val netOk = hasUsableNetwork()
                if (!netOk) throw Exception("Нет сети")
                handler.post { setProgressUi(25, "Загрузка...", "Безопасное соединение") }
                Thread.sleep(250)

                handler.post { setProgressUi(45, "Загрузка...", "Подключение к интернету") }
                val reachable = softReachability()
                if (!reachable) throw Exception("Сервер недоступен")
                Thread.sleep(200)

                handler.post { setProgressUi(70, "Загрузка...", "Загрузка моделей") }
                prepareLocal()
                Thread.sleep(200)
                handler.post { setProgressUi(100, "Готово", "Всё готово") }
                ok = true
            } catch (_: Exception) {
                ok = false
            }

            handler.post {
                if (ok) onLoadSuccess() else onLoadFail()
            }
        }
    }

    private fun hasUsableNetwork(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    private fun softReachability(): Boolean {
        val urls = listOf(
            "https://clients3.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
            "https://connectivitycheck.gstatic.com/generate_204"
        )
        for (u in urls) {
            try {
                val c = (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2500
                    readTimeout = 2500
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                }
                val code = c.responseCode
                c.disconnect()
                if (code in 200..399 || code == 204) return true
            } catch (_: Exception) {
            }
        }
        return hasUsableNetwork()
    }

    private fun prepareLocal() {
        try {
            getSharedPreferences("nanoai", MODE_PRIVATE)
            filesDir.resolve("NanoAI/Files").mkdirs()
            SessionsManager.registerCurrentSession(this)
        } catch (_: Exception) {}
    }

    private fun onLoadSuccess() {
        if (attempt > 1) {
            setStatusText("Успешно", "Соединение восстановлено")
        } else {
            setStatusText("Готово", "Запуск Yourbit AI")
        }
        playSuccessCue { goNext() }
        handler.postDelayed({ if (!finished) goNext() }, 1500)
    }

    private fun onLoadFail() {
        setStatusText("Ошибка загрузки", "Повторная попытка...")
        setProgressUi(progress.coerceAtMost(40))
        playFailCue()

        handler.postDelayed({
            if (finished) return@postDelayed
            attempt = 2
            setStatusText("Загрузка...", "Повторное подключение")
            io.execute {
                var ok = false
                try {
                    ok = hasUsableNetwork() && softReachability()
                    prepareLocal()
                } catch (_: Exception) {
                    ok = false
                }
                handler.post {
                    if (ok) {
                        onLoadSuccess()
                    } else {
                        setStatusText("Ошибка загрузки", "Повтор...")
                        playFailCue()
                        handler.postDelayed({
                            attempt++
                            setStatusText("Загрузка...", "Повторное подключение")
                            startLoadingPipeline()
                        }, 2200)
                    }
                }
            }
        }, 1800)
    }

    private fun goNext() {
        if (finished) return
        finished = true
        val prefs = getSharedPreferences("nanoai", MODE_PRIVATE)
        val next = when {
            !prefs.getBoolean("welcome_seen", false) -> Intent(this, WelcomeActivity::class.java)
            else -> Intent(this, MainActivity::class.java)
        }
        startActivity(next)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
    }
}
