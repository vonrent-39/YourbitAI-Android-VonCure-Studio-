
package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.github.junrar.Archive
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

class FilePreviewActivity : NanoBaseActivity() {
    override val glassWindow: Boolean get() = false
    private var filePath: String? = null
    private var fileUri: Uri? = null
    private var mime: String = "*/*"
    private var title: String = "Файл"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fileUri = intent?.data
        title = intent?.getStringExtra("title") ?: "Файл"
        mime = intent?.getStringExtra("mime") ?: "*/*"
        filePath = intent?.getStringExtra("path")

        if (filePath == null && fileUri?.scheme == "file") {
            filePath = fileUri?.path
        }
        if (mime == "*/*" || mime.isBlank()) {
            mime = guessMime(title)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF000000.toInt()))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(root)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        head.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            setPadding(0, 0, dp(12), 0)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
        head.addView(TextView(this).apply {
            text = when {
                mime.startsWith("image/") -> "Просмотр изображения"
                else -> "Просмотр файла"
            }
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(head)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF1A1A1E.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(ImageView(this@FilePreviewActivity).apply {
                val isArchive = mime.contains("zip") || mime.contains("rar")
                setImageResource(
                    when {
                        mime.startsWith("image/") -> R.drawable.ic_gallery
                        isArchive -> R.drawable.ic_archive_box_asset
                        else -> R.drawable.ic_doc
                    }
                )
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setColorFilter(Th.fg(0xFFFFFFFF.toInt()))
            })
            val col = LinearLayout(this@FilePreviewActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(this@FilePreviewActivity).apply {
                text = title
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 14f
            })
            col.addView(TextView(this@FilePreviewActivity).apply {
                text = sizeLabel()
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 12f
            })
            addView(col)
        })

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        try {
            when {
                mime.startsWith("image/") || title.matches(Regex(".*\\.(png|jpe?g|webp|gif)$", RegexOption.IGNORE_CASE)) ->
                    showImage(body)
                mime.startsWith("audio/") || title.matches(Regex(".*\\.(mp3|m4a|wav|ogg|opus|flac)$", RegexOption.IGNORE_CASE)) ->
                    showAudio(body)
                mime.contains("zip") || title.endsWith(".zip", true) -> showZip(body)
                mime.contains("rar") || title.endsWith(".rar", true) -> showRar(body)
                else -> showText(body)
            }
        } catch (e: Exception) {
            body.addView(tv("Не удалось открыть: ${e.message}", Th.fg(0xFFFF8A80.toInt())))
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        }
        if (filePath != null) {
            val isAudio = mime.startsWith("audio/") || title.matches(Regex(".*\\.(mp3|m4a|wav|ogg|opus|flac)$", RegexOption.IGNORE_CASE))
            val isTextLike = !mime.startsWith("image/") && !mime.contains("zip") && !mime.contains("rar") && !isAudio
            if (HtmlPreviewActivity.isPreviewable(title)) {
                actions.addView(actionBtn("Предпросмотр", R.drawable.ic_play) {
                    HtmlPreviewActivity.openFile(this, File(filePath!!))
                })
            }
            if (isTextLike) {
                actions.addView(actionBtn("Редактировать", R.drawable.ic_pencil_asset) {
                    startActivity(Intent(this, TextEditorActivity::class.java).apply {
                        putExtra("path", filePath)
                        putExtra("title", title)
                    })
                })
            }
            actions.addView(actionBtn("Скачать", R.drawable.ic_download) {
                downloadToDownloads(File(filePath!!), title)
            })
            actions.addView(actionBtn("Удалить", R.drawable.ic_trash) {
                styledConfirmDialog(
                    title = "Удалить файл?",
                    message = "«$title» будет удалён без возможности восстановить.",
                    positiveText = "Удалить",
                    destructive = true
                ) {
                    try {
                        File(filePath!!).delete()
                        Toast.makeText(this, Loc.t("Удалено"), Toast.LENGTH_SHORT).show()
                        finish()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
        if (actions.childCount > 0) {
            val lastParams = actions.getChildAt(actions.childCount - 1).layoutParams as LinearLayout.LayoutParams
            lastParams.marginEnd = 0
        }
        root.addView(actions)
        root.addView(infoCard())
    }

    private fun downloadToDownloads(f: File, displayName: String) {
        try {
            if (!f.exists()) {
                Toast.makeText(this, Loc.t("Файл не найден"), Toast.LENGTH_SHORT).show()
                return
            }
            val resolver = contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, guessMime(displayName))
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
                android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val outUri = resolver.insert(collection, values) ?: throw Exception("Не удалось создать файл в Downloads")
            resolver.openOutputStream(outUri)?.use { outs -> f.inputStream().use { ins -> ins.copyTo(outs) } }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                values.clear()
                values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            }
            Toast.makeText(this, Loc.t("Сохранено в Загрузки: $displayName"), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Скачивание: ${e.message}"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showImage(body: LinearLayout) {
        val path = filePath
        if ((mime == "image/gif" || title.endsWith(".gif", true)) && !path.isNullOrBlank()) {
            val gifView = AnimatedGifView(this).apply {
                loadFromFile(path)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            if (!gifView.loadError) {
                body.addView(FrameLayout(this).apply {
                    setBackgroundColor(Th.bg(0xFF111111.toInt()))
                    addView(gifView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER })
                })
                return
            }
        }
        val stream = openStream()
        if (stream == null) {
            body.addView(tv("Нет доступа к файлу", Th.fg(0xFFFF8A80.toInt())))
            return
        }
        stream.use { ins ->
            val bmp = BitmapFactory.decodeStream(ins)
            if (bmp != null) {
                body.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Th.bg(0xFF111111.toInt()))
                })
            } else {
                body.addView(tv("Не удалось декодировать изображение", Th.fg(0xFFFF8A80.toInt())))
            }
        }
    }

    private fun showZip(body: LinearLayout) {
        body.addView(tv("Нажмите на файл или на иконку карандаша — откроется редактор", Th.fg(0xFF777777.toInt()), 12f))
        data class Entry(val name: String, val size: Long, val text: String?)
        val entries = mutableListOf<Entry>()
        val textExt = Regex(
            """.*\.(txt|md|markdown|json|json5|csv|tsv|log|kt|kts|java|py|pyw|rb|php|go|rs|swift|c|h|cpp|hpp|cc|cs|m|mm|dart|lua|pl|r|sql|sh|bash|zsh|bat|ps1|xml|html?|css|scss|less|js|mjs|cjs|jsx|ts|tsx|vue|gradle|properties|ya?ml|toml|ini|cfg|conf|env|gitignore|gitattributes|editorconfig|dockerfile|makefile|cmake|proguard|pro)$""",
            RegexOption.IGNORE_CASE
        )
        val binaryExt = Regex(
            """.*\.(png|jpe?g|gif|webp|bmp|ico|zip|rar|7z|gz|tar|apk|aab|dex|so|jar|class|ttf|otf|woff2?|mp3|mp4|wav|ogg|flac|m4a|mov|avi|mkv|pdf|db|sqlite)$""",
            RegexOption.IGNORE_CASE
        )
        fun looksBinary(bytes: ByteArray): Boolean {
            val sample = bytes.take(4000)
            return sample.any { it.toInt() == 0 }
        }
        openStream()?.use { ins ->
            ZipInputStream(BufferedInputStream(ins)).use { zis ->
                var e = zis.nextEntry
                var n = 0
                while (e != null && n < 300) {
                    if (!e.isDirectory) {
                        val name = e.name
                        val size = e.size
                        val hasKnownExt = textExt.matches(name)
                        val hasBinaryExt = binaryExt.matches(name)
                        val content = if (hasBinaryExt) null else {
                            try {
                                val bytes = zis.readBytes()
                                if (hasKnownExt) {
                                    String(bytes, Charsets.UTF_8)
                                } else if (bytes.size < 2_000_000 && !looksBinary(bytes)) {
                                    String(bytes, Charsets.UTF_8)
                                } else null
                            } catch (_: Exception) { null }
                        }
                        entries.add(Entry(name, size, content))
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                    n++
                }
            }
        }
        if (entries.isEmpty()) {
            body.addView(tv("Пусто или нет доступа", Th.fg(0xFF888888.toInt())))
            return
        }
        fun openEntryEditor(ent: Entry) {
            val tmp = File(cacheDir, "edit_${System.currentTimeMillis()}_${ent.name.substringAfterLast('/')}")
            tmp.writeText(ent.text!!)
            startActivity(android.content.Intent(this@FilePreviewActivity, TextEditorActivity::class.java).apply {
                putExtra("path", tmp.absolutePath)
                putExtra("title", ent.name.substringAfterLast('/'))
                putExtra("content", ent.text)
            })
        }
        for (ent in entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                if (ent.text != null) setOnClickListener { openEntryEditor(ent) }
            }
            row.addView(TextView(this).apply {
                text = "• ${ent.name}" + if (ent.size >= 0) "  (${formatSize(ent.size)})" else ""
                setTextColor(if (ent.text != null) Th.fg(0xFF8AB4FF.toInt()) else Th.fg(0xFFCCCCCC.toInt()))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (ent.text != null) {
                row.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_pencil_asset)
                    setColorFilter(Th.fg(0xFF8AB4FF.toInt()))
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(6) }
                    contentDescription = "Редактировать"
                    applyPressAnimation(PressAnimStyle.SOFT)
                    setOnClickListener { openEntryEditor(ent) }
                })
            }
            body.addView(row)
        }
    }

    private fun showRar(body: LinearLayout) {
        val tmp = File(cacheDir, "preview_${System.currentTimeMillis()}.rar")
        try {
            openStream()?.use { ins -> FileOutputStream(tmp).use { outs -> ins.copyTo(outs) } }
                ?: run {
                    body.addView(tv("Нет доступа", Th.fg(0xFFFF8A80.toInt())))
                    return
                }
            val arch = Archive(tmp)
            try {
                var n = 0
                for (hdr in arch.fileHeaders) {
                    if (n >= 300) break
                    if (hdr.isDirectory) continue
                    val name = try { hdr.fileNameString } catch (_: Exception) { "?" }
                    body.addView(tv("• $name  (${hdr.fullUnpackSize} B)", Th.fg(0xFFCCCCCC.toInt())))
                    n++
                }
                if (n == 0) body.addView(tv("Пусто", Th.fg(0xFF888888.toInt())))
            } finally {
                try { arch.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            body.addView(tv("RAR: ${e.message}", Th.fg(0xFFFF8A80.toInt())))
        } finally {
            tmp.delete()
        }
    }

    private var audioPlayer: MediaPlayer? = null
    private val audioHandler = Handler(Looper.getMainLooper())
    private var audioTicker: Runnable? = null

    private fun showAudio(body: LinearLayout) {
        val path = filePath
        val f = path?.let { File(it) }
        if (f == null || !f.exists() || !f.canRead()) {
            showText(body)
            return
        }

        val player = MediaPlayer()
        val prepared = try {
            player.setDataSource(f.absolutePath)
            player.prepare()
            true
        } catch (_: Exception) {
            false
        }
        if (!prepared) {
            player.release()
            showText(body)
            return
        }
        audioPlayer = player

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF1A1A1E.toInt()))
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val playPauseBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF7E57C2.toInt()))
                cornerRadius = dp(24).toFloat()
            }
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
        }
        row.addView(playPauseBtn)

        val seekBar = SeekBar(this).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(seekBar)

        val timeText = TextView(this).apply {
            text = "0:00"
            setTextColor(Th.fg(0xFFAAAAAA.toInt()))
            textSize = 12f
            setPadding(dp(10), 0, 0, 0)
        }
        row.addView(timeText)
        card.addView(row)

        fun formatTime(ms: Int): String {
            val totalSec = ms / 1000
            val m = totalSec / 60
            val s = totalSec % 60
            return String.format(Locale.US, "%d:%02d", m, s)
        }

        fun stopTicker() {
            audioTicker?.let { audioHandler.removeCallbacks(it) }
            audioTicker = null
        }

        fun startTicker() {
            stopTicker()
            val ticker = object : Runnable {
                override fun run() {
                    val p = audioPlayer ?: return
                    if (!p.isPlaying) return
                    val dur = p.duration.coerceAtLeast(1)
                    seekBar.progress = (p.currentPosition.toLong() * 1000 / dur).toInt()
                    timeText.text = formatTime(p.currentPosition)
                    audioHandler.postDelayed(this, 300)
                }
            }
            audioTicker = ticker
            audioHandler.post(ticker)
        }

        playPauseBtn.applyPressAnimation(PressAnimStyle.SOFT)
        playPauseBtn.setOnClickListener {
            val p = audioPlayer ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                playPauseBtn.setImageResource(R.drawable.ic_play)
                stopTicker()
            } else {
                p.start()
                playPauseBtn.setImageResource(R.drawable.ic_pause)
                startTicker()
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = audioPlayer ?: return
                p.seekTo((p.duration.toLong() * progress / 1000).toInt())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        player.setOnCompletionListener {
            playPauseBtn.setImageResource(R.drawable.ic_play)
            seekBar.progress = 0
            timeText.text = "0:00"
            stopTicker()
        }

        body.addView(card)
    }

    private fun showText(body: LinearLayout) {
        val bytes = openStream()?.use { it.readBytes() } ?: ByteArray(0)
        val preview = try {
            when {
                title.endsWith(".pdf", true) || mime.equals("application/pdf", true) ->
                    OfficeDocumentCodec.extractPdf(this, bytes).take(16000)
                title.endsWith(".docx", true) || mime.contains("wordprocessingml", true) ->
                    OfficeDocumentCodec.extractDocx(bytes).take(16000)
                else -> String(bytes.take(16000).toByteArray(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            if (title.endsWith(".pdf", true) || title.endsWith(".docx", true))
                "Не удалось прочитать документ: ${e.message}"
            else "(бинарный, ${bytes.size} байт)"
        }
        val shown = if (preview.isBlank()) "(пусто)" else preview
        val ext = title.substringAfterLast('.', "").lowercase()
        if (ext == "svg" && preview.isNotBlank()) {
            // Rendered picture first, source code below
            val full = try { String(bytes.take(400_000).toByteArray(), Charsets.UTF_8) } catch (_: Exception) { preview }
            SvgPreview.build(this, full, null)?.let { body.addView(it) }
        }
        val tvView = tv(shown, Th.fg(0xFFE0E0E0.toInt()), 13f)
        if (ext.isNotEmpty() && preview.isNotBlank() && EconomyPrefs.syntaxHighlightEnabled(this)) {
            tvView.text = SyntaxHighlighter.highlight(shown, ext)
        }
        body.addView(tvView)
    }

    private fun openStream(): InputStream? {
        try {
            filePath?.let { p ->
                val f = File(p)
                if (f.exists() && f.canRead()) return FileInputStream(f)
            }
            fileUri?.let { return contentResolver.openInputStream(it) }
        } catch (_: Exception) {}
        return null
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f КБ", bytes / 1024.0)
            else -> String.format(java.util.Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0))
        }
    }

    private fun sizeLabel(): String {
        try {
            filePath?.let {
                val f = File(it)
                if (f.exists()) return formatSize(f.length())
            }
            fileUri?.let { u ->
                contentResolver.openFileDescriptor(u, "r")?.use {
                    return formatSize(it.statSize)
                }
            }
        } catch (_: Exception) {}
        return "—"
    }

    private fun infoCard(): LinearLayout {
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF1A1A1E.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        info.addView(tv("Информация", Th.fg(0xFFFFFFFF.toInt()), 15f, true))
        info.addView(tv(
            "Тип: $mime\nРазмер: ${sizeLabel()}\nИмя: $title" +
                (filePath?.let { "\nПуть: $it" } ?: ""),
            Th.fg(0xFFAAAAAA.toInt()), 13f
        ))
        return info
    }

    private fun actionBtn(label: String, icon: Int, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF2A2A30.toInt()))
                cornerRadius = dp(12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(8)
            layoutParams = lp
            addView(ImageView(this@FilePreviewActivity).apply {
                setImageResource(icon)
                setColorFilter(Th.fg(0xFFFFFFFF.toInt()))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(6) }
            })
            addView(TextView(this@FilePreviewActivity).apply {
                text = label
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 13f
                gravity = Gravity.CENTER
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = false
            })
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { onClick() }
        }

    private fun tv(t: String, color: Int, size: Float = 14f, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            setTextColor(color)
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            if (size <= 13f) typeface = android.graphics.Typeface.MONOSPACE
        }

    private fun guessMime(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        name.endsWith(".zip", true) -> "application/zip"
        name.endsWith(".rar", true) -> "application/x-rar-compressed"
        else -> "*/*"
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        audioTicker?.let { audioHandler.removeCallbacks(it) }
        audioPlayer?.let { runCatching { it.release() } }
        audioPlayer = null
        super.onDestroy()
    }
}
