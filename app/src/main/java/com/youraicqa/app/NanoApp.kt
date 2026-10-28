package com.youraicqa.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

class NanoApp : Application() {
    private val handler = Handler(Looper.getMainLooper())
    private var started = 0
    private var lastTick = 0L

    private val ticker = object : Runnable {
        override fun run() {
            flushForegroundTime()
            if (started > 0) handler.postDelayed(this, 30_000L)
        }
    }

    private val modelsPoll = object : Runnable {
        override fun run() {
            BuiltinModels.refreshInBackground(this@NanoApp)
            if (started > 0) handler.postDelayed(this, pollDelay())
        }
    }

    private fun flushForegroundTime() {
        val now = System.currentTimeMillis()
        if (lastTick > 0L) AchievementsStore.addForegroundTime(this, (now - lastTick).coerceIn(0L, 120_000L))
        lastTick = now
    }

    private fun isRestartProcess(): Boolean {
        val name = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else {
                val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
            }
        } catch (_: Exception) { null }
        return name != null && name.endsWith(":restart")
    }

    override fun onCreate() {
        super.onCreate()
        // The helper process that relaunches the app must not touch app data (prefs are not multi-process safe)
        if (isRestartProcess()) return
        BackupManager.runPendingWipe(this)
        CrashReporter.install(this)
        Loc.init(this)
        CustomMathSymbols.load(this)
        BuiltinModels.init(this)
        ModelAuthorityPrefs.loadBuiltinModes(this)
        UiPrefs.applyGlobalFontIfNeeded(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    Transport.refreshInBackground(this@NanoApp)
                    Cloud.syncInBackground(this@NanoApp)
                    BuiltinModels.refreshInBackground(this@NanoApp)
                    lastTick = System.currentTimeMillis()
                    handler.removeCallbacks(ticker)
                    handler.postDelayed(ticker, 30_000L)
                    handler.removeCallbacks(modelsPoll)
                    handler.postDelayed(modelsPoll, pollDelay())
                }
            }
            override fun onActivityResumed(activity: Activity) { cur = WeakReference(activity) }
            override fun onActivityPaused(activity: Activity) {
                if (cur?.get() === activity) cur = null
            }
            override fun onActivityStopped(activity: Activity) {
                if (started > 0 && --started == 0) {
                    flushForegroundTime()
                    lastTick = 0L
                    handler.removeCallbacks(ticker)
                    handler.removeCallbacks(modelsPoll)
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        @Volatile private var cur: WeakReference<Activity>? = null

        fun currentActivity(): Activity? = cur?.get()
    }

    private fun pollDelay(): Long =
        BuiltinModels.pollIntervalMs() * (if (EconomyPrefs.slowModelSync(this)) 4 else 1)
}
