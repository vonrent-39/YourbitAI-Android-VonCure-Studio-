
package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FilesActivity : NanoBaseActivity() {
    private lateinit var list: LinearLayout
    private val pickReq = 4401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AchievementsStore.onTabOpened(this, "library")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF000000.toInt()))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(root)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        head.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            setPadding(0, 0, dp(12), 0)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_library)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(0); marginEnd = dp(8) }
        })
        head.addView(TextView(this).apply {
            text = "Библиотека"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = "＋"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf", "application/zip", "application/x-rar-compressed", "text/*", "*/*"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(i, "Импорт файла"), pickReq)
            }
        })
        root.addView(head)

        root.addView(TextView(this).apply {
            text = "Папка приложения. Сюда попадают импорт и ZIP от нейросети."
            setTextColor(Th.fg(0xFF777777.toInt()))
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })

        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized) refresh()
    }

    private fun filesDirApp(): File =
        File(filesDir, "NanoAI/Files").apply { mkdirs() }

    private fun refresh() {
        list.removeAllViews()
        val files = filesDirApp().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()
        if (files.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Пока пусто.\nНажмите ＋ чтобы добавить фото или документ.\nИли попросите нейросеть: «создайте zip с файлом …»"
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            })
            return
        }
        val fmt = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(Th.bg(0xFF1A1A1E.toInt()))
                    cornerRadius = dp(14).toFloat()
                }
                setPadding(dp(14), dp(14), dp(14), dp(14))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(10)
                layoutParams = lp
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener { openFile(f) }
            }
            row.addView(ImageView(this).apply {
                setImageResource(fileIconRes(f.name))
                setColorFilter(Th.fg(0xFFFFFFFF.toInt()))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = f.name
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 15f
                maxLines = 2
            })
            col.addView(TextView(this).apply {
                text = run {
                    val b = f.length()
                    val sz = when {
                        b < 1024 -> "$b Б"
                        b < 1024 * 1024 -> String.format(Locale.US, "%.1f КБ", b / 1024.0)
                        else -> String.format(Locale.US, "%.2f МБ", b / (1024.0 * 1024.0))
                    }
                    "$sz · ${fmt.format(Date(f.lastModified()))}"
                }
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 12f
            })
            row.addView(col)
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_download)
                setColorFilter(Th.fg(0xFFCCCCCC.toInt()))
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = "Скачать"
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener { downloadToDownloads(f) }
            })
            list.addView(row)
        }
    }

    private fun downloadToDownloads(f: File) {
        try {
            if (!f.exists()) {
                Toast.makeText(this, Loc.t("Файл не найден"), Toast.LENGTH_SHORT).show()
                return
            }
            val resolver = contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, guessMime(f.name))
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
            Toast.makeText(this, Loc.t("Сохранено в Загрузки: ${f.name}"), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Скачивание: ${e.message}"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFile(f: File) {
        if (!f.exists()) {
            Toast.makeText(this, Loc.t("Файл не найден"), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, FilePreviewActivity::class.java).apply {
            putExtra("title", f.name)
            putExtra("mime", guessMime(f.name))
            putExtra("path", f.absolutePath)
        })
    }

    private fun fileIconRes(name: String): Int {
        val n = name.lowercase()
        return when {
            n.endsWith(".zip") || n.endsWith(".rar") -> R.drawable.ic_file_zip
            n.endsWith(".py") -> R.drawable.ic_file_py
            n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log") -> R.drawable.ic_file_txt
            n.endsWith(".html") || n.endsWith(".htm") -> R.drawable.ic_file_html
            n.endsWith(".jpg") -> R.drawable.ic_file_jpg
            n.endsWith(".jpeg") -> R.drawable.ic_file_jpeg
            n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".gif") -> R.drawable.ic_file_jpg
            else -> R.drawable.ic_file_unknown
        }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".zip", true) -> "application/zip"
        name.endsWith(".rar", true) -> "application/x-rar-compressed"
        name.endsWith(".txt", true) || name.endsWith(".md", true) -> "text/plain"
        else -> "*/*"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickReq || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            var name = "file_${System.currentTimeMillis()}"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) name = n
                }
            }
            name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dest = File(filesDirApp(), name)
            contentResolver.openInputStream(uri)?.use { ins ->
                dest.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: throw Exception("Не удалось прочитать файл")
            Toast.makeText(this, Loc.t("Сохранено: ${dest.name}"), Toast.LENGTH_SHORT).show()
            refresh()
            openFile(dest)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Ошибка", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
