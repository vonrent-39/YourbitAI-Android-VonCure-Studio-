package com.youraicqa.app.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.youraicqa.app.MainActivity
import com.youraicqa.app.R

class LocalGenerationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ensureChannels(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_RUN)
            .setContentTitle("Yourbit AI")
            .setContentText("Локальная модель формирует ответ…")
            .setSmallIcon(R.drawable.ic_plug_plugins)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_RUN, n)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_RUN = "nanoai_local_gen_run"
        private const val CHANNEL_DONE = "nanoai_local_gen_done"
        private const val NOTIF_RUN = 7101
        private const val NOTIF_DONE = 7102
        private val handler = Handler(Looper.getMainLooper())
        @Volatile private var appCtx: Context? = null
        private var stopRunnable: Runnable? = null

        fun appContextOrNull(): Context? = appCtx

        private fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_RUN) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_RUN, "Локальная модель работает", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Показывается, пока модель на устройстве формирует ответ" }
                )
            }
            if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_DONE, "Ответ локальной модели готов", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Ответ дописан в чат, пока приложение было закрыто" }
                )
            }
        }

        fun start(ctx: Context) {
            appCtx = ctx.applicationContext
            stopRunnable?.let { handler.removeCallbacks(it) }
            stopRunnable = null
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LocalGenerationService::class.java))
            } catch (_: Exception) {
            }
        }

        fun stopIfIdle(isIdle: () -> Boolean) {
            val ctx = appCtx ?: return
            stopRunnable?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                if (isIdle()) {
                    try { ctx.stopService(Intent(ctx, LocalGenerationService::class.java)) } catch (_: Exception) {}
                }
                stopRunnable = null
            }
            stopRunnable = r
            handler.postDelayed(r, 1500L)
        }

        fun notifyDone(ctx: Context, modelName: String) {
            try {
                if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
                ensureChannels(ctx)
                val open = PendingIntent.getActivity(
                    ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
                    .setContentTitle("Yourbit AI")
                    .setContentText("Ответ модели «${modelName.take(60)}» готов")
                    .setSmallIcon(R.drawable.ic_plug_plugins)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                NotificationManagerCompat.from(ctx).notify(NOTIF_DONE, n)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }
}
