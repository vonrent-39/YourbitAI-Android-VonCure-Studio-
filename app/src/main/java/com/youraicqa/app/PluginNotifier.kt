package com.youraicqa.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object PluginNotifier {
    private const val CHANNEL_ID = "nanoai_plugins"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Плагины Yourbit AI", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Уведомления от установленных плагинов"
            }
        )
    }

    fun notify(context: Context, pluginId: String, title: String, message: String) {
        try {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title.take(200).ifBlank { "Yourbit AI" })
                .setContentText(message.take(500))
                .setSmallIcon(R.drawable.ic_plug_plugins)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(pluginId.hashCode(), notification)
        } catch (_: SecurityException) {
        } catch (_: Exception) { }
    }
}
