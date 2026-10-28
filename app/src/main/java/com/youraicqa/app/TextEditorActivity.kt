package com.youraicqa.app

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class TextEditorActivity : NanoBaseActivity() {
    private var path: String? = null
    private lateinit var editor: EditText
    private lateinit var lineNumbers: TextView
    private lateinit var editToggle: TextView
    private var editorKeyListener: android.text.method.KeyListener? = null
    private var editMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra("path")
        val title = intent.getStringExtra("title") ?: "Редактор"
        val initial = intent.getStringExtra("content") ?: run {
            path?.let { p ->
                try {
                    val f = File(p)
                    when (f.extension.lowercase()) {
                        "docx" -> OfficeDocumentCodec.extractDocx(f.readBytes())
                        "pdf" -> OfficeDocumentCodec.extractPdf(this, f.readBytes())
                        else -> f.readText()
                    }
                } catch (e: Exception) { "Не удалось прочитать документ: ${e.message}" }
            } ?: ""
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF000000.toInt()))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        setContentView(root)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
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
            text = title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1
        })
        editToggle = TextView(this).apply {
            text = "✎ Редактировать"
            setTextColor(Th.fg(0xFF8AB4FF.toInt()))
            textSize = 15f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                if (!editMode) {
                    setEditMode(true)
                } else {
                    save()
                }
            }
        }
        head.addView(editToggle)
        root.addView(head)

        val fileExt = (path?.substringAfterLast('.') ?: title.substringAfterLast('.')).lowercase()
        if (fileExt == "pdf" || fileExt == "docx") {
            root.addView(TextView(this).apply {
                text = if (fileExt == "pdf")
                    "PDF редактируется как текст. При сохранении создаётся копия с новой вёрсткой; исходные изображения и оформление не переносятся."
                else
                    "DOCX редактируется как текст. При сохранении создаётся копия; исходное форматирование и встроенные изображения не переносятся."
                setTextColor(Th.fg(0xFFB6B6C0.toInt()))
                textSize = 12f
                setPadding(dp(4), 0, dp(4), dp(10))
            })
        }

        val editorTextSize = 14f
        val editorPaddingV = dp(12)

        lineNumbers = TextView(this).apply {
            text = "1"
            setTextColor(Th.fg(0xFF5A5A62.toInt()))
            textSize = editorTextSize
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.END
            setPadding(dp(10), editorPaddingV, dp(8), editorPaddingV)
            setLineSpacing(0f, 1f)
            includeFontPadding = false
        }

        editor = EditText(this).apply {
            setText(initial)
            setTextColor(Th.fg(0xFFE8E8E8.toInt()))
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            textSize = editorTextSize
            gravity = Gravity.TOP or Gravity.START
            background = null
            setPadding(dp(12), editorPaddingV, dp(12), editorPaddingV)
            typeface = android.graphics.Typeface.MONOSPACE
            setLineSpacing(0f, 1f)
            includeFontPadding = false
            isVerticalScrollBarEnabled = false
            setHorizontallyScrolling(true)
            maxLines = Int.MAX_VALUE
        }
        editorKeyListener = editor.keyListener
        setEditMode(false)

        fun updateLineNumbers() {
            val count = editor.lineCount.coerceAtLeast(1)
            lineNumbers.text = (1..count).joinToString("\n")
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateLineNumbers() }
        })
        editor.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                updateLineNumbers()
                editor.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        val codeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        codeRow.addView(lineNumbers, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val hScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(editor, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        codeRow.addView(hScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val vScroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF141418.toInt()))
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            addView(codeRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(vScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
    }

    private fun setEditMode(enabled: Boolean) {
        editMode = enabled
        editor.isFocusable = enabled
        editor.isFocusableInTouchMode = enabled
        editor.isCursorVisible = enabled
        editor.keyListener = if (enabled) editorKeyListener else null
        if (!enabled) {
            editor.clearFocus()
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(editor.windowToken, 0)
        } else {
            editor.requestFocus()
            editor.setSelection(editor.text.length)
        }
        editToggle.text = if (enabled) "Сохранить" else "✎ Редактировать"
        editToggle.setTextColor(if (enabled) Th.fg(0xFF7CFFB2.toInt()) else Th.fg(0xFF8AB4FF.toInt()))
    }

    private fun save() {
        val p = path
        if (p.isNullOrBlank()) {
            try {
                val dir = filesDir.resolve("NanoAI/Files").apply { mkdirs() }
                val name = intent.getStringExtra("title") ?: "note_${System.currentTimeMillis()}.txt"
                val f = dir.resolve(name)
                when (f.extension.lowercase()) {
                    "docx" -> f.writeBytes(OfficeDocumentCodec.createDocx(editor.text.toString()))
                    "pdf" -> f.writeBytes(OfficeDocumentCodec.createPdf(this, editor.text.toString()))
                    else -> f.writeText(editor.text.toString())
                }
                Toast.makeText(this, Loc.t("Сохранено: ${f.name}"), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            val source = File(p)
            val ext = source.extension.lowercase()
            // Office-format text extraction can't preserve the full original layout; save edits
            // into a sibling copy so the user's original PDF/DOCX is never silently destroyed.
            val output = if (ext == "pdf" || ext == "docx") {
                File(source.parentFile, "${source.nameWithoutExtension}_edited.$ext")
            } else source
            when (ext) {
                "docx" -> output.writeBytes(OfficeDocumentCodec.rewriteDocx(source.readBytes(), editor.text.toString()))
                "pdf" -> output.writeBytes(OfficeDocumentCodec.createPdf(this, editor.text.toString()))
                else -> source.writeText(editor.text.toString())
            }
            Toast.makeText(this, if (output != source) "Сохранено как ${output.name}" else Loc.t("Сохранено"), Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            if ((path?.substringAfterLast('.') ?: "").lowercase() in setOf("pdf", "docx")) {
                showOfficeErrorPopup("${path?.substringAfterLast('.')?.uppercase() ?: "DOCX"}: ${e.message ?: "Не удалось сохранить документ"}")
            } else Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOfficeErrorPopup(raw: String) {
        val item = AppErrorUi.classify("document file error: $raw")
        val body = TextView(this).apply {
            text = item.description + "\n\n" + raw.take(200)
            setTextColor(Th.fg(0xFFE5E5EA.toInt()))
            textSize = 14f
        }
        styledPanelDialog(
            title = "Ошибка E${item.code.toString().padStart(2, '0')} · ${item.title}",
            contentView = body,
            buttons = listOf(StyledDialogButton("Понятно") {})
        )
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
