package com.youraicqa.app

import android.content.Context
import java.io.File
import org.json.JSONObject

object PluginDownloadCapability {
    private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024

    data class Outcome(val ok: Boolean, val reason: String? = null, val bytesWritten: Long = 0)

    fun run(
        baseFilesDir: File,
        connector: PluginHttpConnector,
        allowlist: Set<String>,
        pluginId: String,
        rawUrl: String,
        filename: String,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Outcome {
        val safeName = PluginFileNames.sanitize(filename) ?: return Outcome(false, "invalid_filename")

        val opened = PluginNetCapability.openFollowingRedirects(
            connector, allowlist, rawUrl, "GET", emptyMap(), PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )
        val resp = when (opened) {
            is PluginNetCapability.OpenResult.Error -> return Outcome(false, opened.reason)
            is PluginNetCapability.OpenResult.Opened -> opened.response
        }

        return try {
            if (resp.status !in 200..299) return Outcome(false, "http_${resp.status}")

            val contentLength = resp.headers.entries
                .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                ?.value?.firstOrNull()?.toLongOrNull()
            if (contentLength != null && contentLength > MAX_DOWNLOAD_BYTES) return Outcome(false, "file_too_large")

            val dir = PluginFiles.dirFor(baseFilesDir, pluginId)
            val tempFile = File(dir, "$safeName.part")
            var written = 0L
            var lastReportedPct = -1

            tempFile.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (isCancelled()) {
                        tempFile.delete()
                        return Outcome(false, "cancelled")
                    }
                    val n = resp.body.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > MAX_DOWNLOAD_BYTES) {
                        tempFile.delete()
                        return Outcome(false, "file_too_large")
                    }
                    out.write(buf, 0, n)
                    if (contentLength != null && contentLength > 0) {
                        val pct = ((written * 100) / contentLength).toInt().coerceIn(0, 100)
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }

            val finalFile = File(dir, safeName)
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
            onProgress(100)
            PluginFiles.mirrorDownloadToLibrary(baseFilesDir, safeName, finalFile)
            Outcome(true, bytesWritten = written)
        } catch (e: Exception) {
            Outcome(false, e.message ?: "download_failed")
        } finally {
            resp.close()
        }
    }

    fun run(
        context: Context,
        connector: PluginHttpConnector,
        allowlist: Set<String>,
        pluginId: String,
        rawUrl: String,
        filename: String,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ): Outcome = run(
        context.applicationContext.filesDir, connector, allowlist, pluginId, rawUrl, filename, isCancelled, onProgress
    )

    fun outcomeToJson(outcome: Outcome, filename: String): JSONObject =
        JSONObject()
            .put("ok", outcome.ok)
            .put("filename", filename)
            .put("bytesWritten", outcome.bytesWritten)
            .put("error", outcome.reason)
}
