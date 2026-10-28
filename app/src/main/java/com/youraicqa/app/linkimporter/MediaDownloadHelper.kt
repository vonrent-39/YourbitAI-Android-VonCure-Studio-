package com.youraicqa.app.linkimporter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

object MediaDownloadHelper {

    fun saveToDownloads(context: Context, f: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, guessMediaMime(f.name))
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val outUri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(outUri)?.use { outs ->
            f.inputStream().use { ins -> ins.copyTo(outs) }
        } ?: return null
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(outUri, values, null, null)
        }
        return outUri
    }

    fun guessMediaMime(name: String): String = when {
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".webm", true) -> "video/webm"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".mov", true) -> "video/quicktime"
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".opus", true) -> "audio/opus"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".md", true) -> "text/markdown"
        name.endsWith(".json", true) -> "application/json"
        else -> "*/*"
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f КБ", bytes / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0))
    }
}
