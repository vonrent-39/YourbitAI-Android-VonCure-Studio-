package com.youraicqa.app

import android.content.Context
import java.io.File
import java.util.Base64

object PluginFiles {
    private const val MAX_FILE_BYTES = 25 * 1024 * 1024

    fun dirFor(baseFilesDir: File, pluginId: String): File {
        val safeId = pluginId.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
        val dir = File(File(baseFilesDir, "plugin_files"), safeId)
        dir.mkdirs()
        return dir
    }

    fun dirFor(context: Context, pluginId: String): File =
        dirFor(context.applicationContext.filesDir, pluginId)

    private fun libraryDirFor(baseFilesDir: File): File =
        File(baseFilesDir, "NanoAI/Files").apply { mkdirs() }

    private fun mirrorToLibrary(baseFilesDir: File, filename: String, bytes: ByteArray) {
        try {
            val libDir = libraryDirFor(baseFilesDir)
            var candidate = File(libDir, filename)
            if (candidate.exists()) {
                val dot = filename.lastIndexOf('.')
                val base = if (dot > 0) filename.substring(0, dot) else filename
                val ext = if (dot > 0) filename.substring(dot) else ""
                var n = 1
                while (candidate.exists()) {
                    candidate = File(libDir, "$base ($n)$ext")
                    n++
                }
            }
            candidate.writeBytes(bytes)
        } catch (_: Exception) {
        }
    }

    data class SaveResult(val ok: Boolean, val error: String? = null, val bytesWritten: Int = 0)

    fun save(baseFilesDir: File, pluginId: String, filename: String, data: String, encoding: String?): SaveResult {
        val safeName = PluginFileNames.sanitize(filename) ?: return SaveResult(false, "invalid_filename")
        val bytes = try {
            if (encoding == "base64") Base64.getDecoder().decode(data) else data.toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            return SaveResult(false, "invalid_data")
        }
        if (bytes.size > MAX_FILE_BYTES) return SaveResult(false, "file_too_large")
        return try {
            val dir = dirFor(baseFilesDir, pluginId)
            File(dir, safeName).writeBytes(bytes)
            mirrorToLibrary(baseFilesDir, safeName, bytes)
            SaveResult(true, bytesWritten = bytes.size)
        } catch (e: Exception) {
            SaveResult(false, e.message ?: "write_failed")
        }
    }

    fun save(context: Context, pluginId: String, filename: String, data: String, encoding: String?): SaveResult =
        save(context.applicationContext.filesDir, pluginId, filename, data, encoding)

    fun mirrorDownloadToLibrary(baseFilesDir: File, filename: String, sourceFile: File) {
        try {
            mirrorToLibrary(baseFilesDir, filename, sourceFile.readBytes())
        } catch (_: Exception) {
        }
    }

    fun maxFileBytes() = MAX_FILE_BYTES
}
