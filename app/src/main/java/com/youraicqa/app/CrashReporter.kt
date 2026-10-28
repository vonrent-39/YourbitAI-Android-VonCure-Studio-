package com.youraicqa.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val header = "Time: " + java.util.Date().toString() +
                    "\nThread: " + thread.name +
                    "\nDevice: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                    " (Android " + android.os.Build.VERSION.RELEASE + ", API " + android.os.Build.VERSION.SDK_INT + ")\n\n"
                File(appContext.filesDir, FILE_NAME).writeText(header + sw.toString())
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun take(context: Context): String? {
        return try {
            val f = File(context.applicationContext.filesDir, FILE_NAME)
            if (!f.isFile) return null
            val text = f.readText()
            f.delete()
            text.ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }
}
