package com.youraicqa.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight client log stored only inside Yourbit AI's private sandbox.
 * API keys and message bodies are deliberately never written.
 */
object NanoLog {
    private const val MAX_BYTES = 512 * 1024

    private fun logFile(context: Context): File =
        File(context.applicationContext.filesDir, "NanoAI/logs/client.log").apply {
            parentFile?.mkdirs()
        }

    fun log(context: Context, event: String) {
        try {
            val f = logFile(context)
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = f.readText(Charsets.UTF_8)
                f.writeText(old.takeLast(MAX_BYTES / 2), Charsets.UTF_8)
            }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("[$ts] $event\n", Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun read(context: Context, maxLines: Int = 250): String {
        return try {
            val lines = logFile(context).takeIf { it.exists() }?.readLines(Charsets.UTF_8).orEmpty()
            lines.takeLast(maxLines.coerceIn(1, 2000)).joinToString("\n")
        } catch (_: Exception) { "" }
    }

    fun clear(context: Context) {
        try { logFile(context).delete() } catch (_: Exception) {}
    }
}
