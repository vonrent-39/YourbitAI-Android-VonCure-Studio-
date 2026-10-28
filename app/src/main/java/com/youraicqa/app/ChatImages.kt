package com.youraicqa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object ChatImages {

    class Loaded(val frames: List<String>, val previewPath: String, val gifPath: String?)

    private const val MAX_SIDE = 1024

    fun isGif(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val h = ByteArray(6)
            val read = ins.read(h)
            read == 6 && h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() &&
                h[2] == 'F'.code.toByte() && h[3] == '8'.code.toByte() &&
                (h[4] == '7'.code.toByte() || h[4] == '9'.code.toByte()) && h[5] == 'a'.code.toByte()
        } ?: false
    } catch (_: Exception) { false }

    private fun toJpegBytes(ctx: Context, bmp: Bitmap): ByteArray {
        val maxSide = EconomyPrefs.imageMaxSide(ctx)
        val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        val resized = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        else bmp
        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, EconomyPrefs.imageJpegQuality(ctx), out)
        return out.toByteArray()
    }

    private fun previewDir(ctx: Context): File = ctx.filesDir.resolve("NanoAI/arena_img").apply { mkdirs() }

    fun load(ctx: Context, uri: Uri): Loaded {
        val stamp = "${System.currentTimeMillis()}_${(0..9999).random()}"
        if (isGif(ctx, uri)) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Не удалось открыть файл")
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: throw Exception("Это не ГИФ-анимация")
            if (movie.width() <= 0 || movie.height() <= 0) throw Exception("Повреждённая ГИФ-анимация")
            val gifDir = ctx.filesDir.resolve("NanoAI/gifs").apply { mkdirs() }
            val gifFile = File(gifDir, "gif_$stamp.gif")
            gifFile.writeBytes(bytes)
            val duration = movie.duration().coerceAtLeast(1)
            val timestamps = if (EconomyPrefs.gifSingleFrame(ctx)) listOf(duration / 2)
            else listOf(0, duration / 2, (duration - 1).coerceAtLeast(0))
            val frames = mutableListOf<String>()
            var firstJpeg: ByteArray? = null
            for (t in timestamps) {
                val bmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                movie.setTime(t)
                movie.draw(canvas, 0f, 0f)
                val jpeg = toJpegBytes(ctx, bmp)
                if (firstJpeg == null) firstJpeg = jpeg
                frames.add(Base64.encodeToString(jpeg, Base64.NO_WRAP))
            }
            val prev = File(previewDir(ctx), "p_$stamp.jpg")
            prev.writeBytes(firstJpeg ?: ByteArray(0))
            return Loaded(frames, prev.absolutePath, gifFile.absolutePath)
        }
        val original = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: throw Exception("Это не изображение")
        val jpeg = toJpegBytes(ctx, original)
        val prev = File(previewDir(ctx), "p_$stamp.jpg")
        prev.writeBytes(jpeg)
        return Loaded(listOf(Base64.encodeToString(jpeg, Base64.NO_WRAP)), prev.absolutePath, null)
    }

    fun decodePreview(path: String, maxPx: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxPx && opts.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) { null }
}
