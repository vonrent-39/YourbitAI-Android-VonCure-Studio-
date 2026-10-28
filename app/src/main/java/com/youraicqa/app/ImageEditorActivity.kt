package com.youraicqa.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

class ImageEditorActivity : NanoBaseActivity() {
    override val glassWindow: Boolean get() = false

    companion object {
        const val EXTRA_INPUT_PATH = "input_path"
        const val EXTRA_OUTPUT_PATH = "output_path"
    }

    private lateinit var canvas: ImageEditorCanvasView
    private lateinit var btnCrop: TextView
    private lateinit var btnBrush: TextView
    private lateinit var btnUndo: TextView
    private lateinit var btnApplyCrop: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val inputPath = intent.getStringExtra(EXTRA_INPUT_PATH)
        val bmp = try {
            inputPath?.let { decodeScaled(it, 1600) }
        } catch (_: Throwable) { null }
        if (bmp == null) {
            Toast.makeText(this, Loc.t("Не удалось открыть изображение"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        header.addView(pillButton("Отмена") { finish() })
        header.addView(TextView(this).apply {
            text = "Редактор изображения"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 15f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(pillButton("Готово", accent = true) { saveAndFinish() })
        root.addView(header)

        canvas = ImageEditorCanvasView(this).apply { setBitmap(bmp) }
        root.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(canvas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        btnCrop = toolButton("Обрезать", R.drawable.ic_cut) { setMode(ImageEditorCanvasView.Mode.CROP) }
        btnBrush = toolButton("Кисть", R.drawable.ic_brush) { setMode(ImageEditorCanvasView.Mode.DRAW) }
        btnUndo = toolButton("Отменить", R.drawable.ic_undo) {
            if (canvas.canUndo()) { canvas.undo(); updateToolbarState() }
        }
        btnApplyCrop = toolButton("Применить обрезку", R.drawable.ic_check) {
            if (!canvas.applyCrop()) Toast.makeText(this, Loc.t("Область обрезки слишком мала"), Toast.LENGTH_SHORT).show()
            updateToolbarState()
        }
        toolbar.addView(btnCrop)
        toolbar.addView(btnBrush)
        toolbar.addView(btnUndo)
        root.addView(toolbar)
        val cropRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        cropRow.addView(btnApplyCrop)
        root.addView(cropRow)

        setContentView(root)
        updateToolbarState()
    }

    private fun decodeScaled(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val raw = BitmapFactory.decodeFile(path, opts) ?: return null
        return if (raw.isMutable && raw.config == Bitmap.Config.ARGB_8888) raw
        else raw.copy(Bitmap.Config.ARGB_8888, true)
    }

    // Frosted-glass chip: faint gradient fill with a light rim; brighter when active.
    private fun glassBg(active: Boolean, radiusDp: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (active) intArrayOf(0x55FFFFFF, 0x26FFFFFF) else intArrayOf(0x24FFFFFF, 0x0DFFFFFF)
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), if (active) 0x99FFFFFF.toInt() else 0x33FFFFFF)
        }

    private fun setMode(m: ImageEditorCanvasView.Mode) {
        canvas.mode = if (canvas.mode == m) ImageEditorCanvasView.Mode.NONE else m
        updateToolbarState()
    }

    private fun updateToolbarState() {
        fun mark(btn: TextView, active: Boolean) {
            btn.background = glassBg(active, 12)
        }
        mark(btnCrop, canvas.mode == ImageEditorCanvasView.Mode.CROP)
        mark(btnBrush, canvas.mode == ImageEditorCanvasView.Mode.DRAW)
        btnUndo.alpha = if (canvas.canUndo()) 1f else 0.4f
        val cropRowVisible = canvas.mode == ImageEditorCanvasView.Mode.CROP
        btnApplyCrop.visibility = if (cropRowVisible) View.VISIBLE else View.GONE
    }

    private fun toolButton(label: String, iconRes: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        Ico.start(this, iconRes, 16f, Color.WHITE, 8f)
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = glassBg(false, 12)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(8)
        layoutParams = lp
        isClickable = true
        isFocusable = true
        applyPressAnimation(PressAnimStyle.SOFT)
        setOnClickListener { onClick() }
    }

    private fun pillButton(label: String, accent: Boolean = false, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTypeface(typeface, if (accent) Typeface.BOLD else Typeface.NORMAL)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = if (accent) glassBg(true, 100) else glassBg(false, 100)
        isClickable = true
        isFocusable = true
        applyPressAnimation(PressAnimStyle.SOFT)
        setOnClickListener { onClick() }
    }

    private fun saveAndFinish() {
        val bmp = canvas.currentBitmap()
        if (bmp == null) { finish(); return }
        try {
            val dir = filesDir.resolve("NanoAI/edited").apply { mkdirs() }
            val out = File(dir, "edited_${System.currentTimeMillis()}.jpg")
            FileOutputStream(out).use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos) }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_PATH, out.absolutePath))
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Не удалось сохранить: ${e.message}"), Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
        }
        finish()
    }
}
