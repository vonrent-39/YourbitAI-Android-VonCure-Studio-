package com.youraicqa.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object ChatAttach {

    class FileItem(val name: String, val sizeLabel: String, val text: String)

    private const val MAX_CHARS = 60_000

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> String.format("%.1f МБ", bytes / 1024.0 / 1024.0)
    }

    fun isImage(ctx: Context, uri: Uri): Boolean =
        (ctx.contentResolver.getType(uri) ?: "").startsWith("image/")

    fun readFile(ctx: Context, uri: Uri): FileItem {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) c.getString(ni)?.takeIf { it.isNotBlank() }?.let { name = it }
                if (si >= 0) size = c.getLong(si)
            }
        }
        name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val mime = ctx.contentResolver.getType(uri) ?: "*/*"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("Не удалось открыть файл")
        val sizeLabel = formatSize(if (size >= 0) size else bytes.size.toLong())
        val lower = name.lowercase()
        val text = when {
            FileTextExtractor.archiveKind(name, mime) != null ->
                FileTextExtractor.archiveText(ctx.cacheDir, { java.io.ByteArrayInputStream(bytes) }, name, mime, sizeLabel).orEmpty()
            lower.endsWith(".pdf") || mime.equals("application/pdf", true) ->
                "Содержимое PDF-документа: $name\n\n" + OfficeDocumentCodec.extractPdf(ctx, bytes).take(MAX_CHARS)
            lower.endsWith(".docx") || mime.contains("wordprocessingml", true) ->
                "Содержимое DOCX-документа: $name\n\n" + OfficeDocumentCodec.extractDocx(bytes).take(MAX_CHARS)
            else -> FileTextExtractor.plainFileText(name, mime, bytes, sizeLabel)
        }
        return FileItem(name, sizeLabel, text)
    }
}
