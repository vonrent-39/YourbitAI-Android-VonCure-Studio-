package com.youraicqa.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class ChatsActivity : NanoBaseActivity() {
    companion object {
        const val EXTRA_OPEN_CHAT_ID = "open_chat_id"
        const val EXTRA_NEW_CHAT = "new_chat"
        const val RESULT_OPEN = 100
        const val RESULT_NEW = 101
    }

    private val prefs by lazy { getSharedPreferences("nanoai", MODE_PRIVATE) }
    private var selectMode = false
    private val selected = mutableSetOf<String>()
    private var filter = ""

    // Stagger the rows only on first open and on select-mode switches, not on every re-render
    private var animateList = true
    private var popId: String? = null

    private fun animOn() = UiPrefs.animationsEnabled(this) && EconomyPrefs.transitionsEnabled(this)

    data class ChatItem(val id: String, val title: String, val preview: String, val timeMs: Long)

    private fun searchSnippet(id: String, query: String): String? {
        if (query.isBlank()) return null
        return try {
            val hist = JSONArray(prefs.getString("chat_${id}_messages", "[]"))
            for (i in 0 until hist.length()) {
                val m = hist.getJSONObject(i)
                val c = m.opt("content")
                val text = when (c) {
                    is String -> c
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (j in 0 until c.length()) {
                            val p = c.optJSONObject(j) ?: continue
                            if (p.optString("type") == "text") sb.append(p.optString("text"))
                        }
                        sb.toString()
                    }
                    else -> ""
                }.replace(Regex("\\s+"), " ").trim()
                if (text.isEmpty()) continue
                val idx = text.indexOf(query, ignoreCase = true)
                if (idx < 0) continue
                val start = (idx - 20).coerceAtLeast(0)
                val end = (idx + query.length + 24).coerceAtMost(text.length)
                val prefix = if (start > 0) "…" else ""
                val suffix = if (end < text.length) "…" else ""
                return prefix + text.substring(start, end) + suffix
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chats)

        findViewById<View>(R.id.btnClose).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnNew).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnNew).setOnClickListener {
            setResult(RESULT_NEW, Intent().putExtra(EXTRA_NEW_CHAT, true))
            finish()
        }
        findViewById<View>(R.id.btnSelectMode).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnSelectMode).setOnClickListener { setSelectMode(true) }
        findViewById<View>(R.id.btnCancelSelect).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnCancelSelect).setOnClickListener { setSelectMode(false) }
        findViewById<View>(R.id.btnDelete).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnDelete).setOnClickListener { deleteSelected() }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString()?.trim()?.lowercase() ?: ""
                animateList = false
                renderList()
            }
        })

        renderList()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun chatIndex(): JSONArray = JSONArray(prefs.getString("chats_index", "[]"))

    private fun loadItems(): List<ChatItem> {
        val idx = chatIndex()
        val items = mutableListOf<ChatItem>()
        for (i in 0 until idx.length()) {
            val o = idx.getJSONObject(i)
            val id = o.getString("id")
            val title = o.optString("title", "Новый чат")
            val timeMs = id.toLongOrNull() ?: 0L
            val preview = firstAssistantPreview(id)
            items.add(ChatItem(id, title, preview, timeMs))
        }
        return items.sortedByDescending { it.timeMs }
    }

    private fun firstAssistantPreview(id: String): String {
        return try {
            val hist = JSONArray(prefs.getString("chat_${id}_messages", "[]"))
            for (i in 0 until hist.length()) {
                val m = hist.getJSONObject(i)
                if (m.getString("role") == "assistant") {
                    val c = m.opt("content")
                    val text = when (c) {
                        is String -> c
                        is JSONArray -> {
                            val sb = StringBuilder()
                            for (j in 0 until c.length()) {
                                val p = c.optJSONObject(j) ?: continue
                                if (p.optString("type") == "text") sb.append(p.optString("text"))
                            }
                            sb.toString()
                        }
                        else -> ""
                    }.replace(Regex("\\s+"), " ").trim()
                    return if (text.length > 48) text.take(48) + "…" else text
                }
            }
            for (i in 0 until hist.length()) {
                val m = hist.getJSONObject(i)
                if (m.getString("role") == "user") {
                    val c = m.opt("content")
                    val text = if (c is String) c else ""
                    val t = text.replace(Regex("\\s+"), " ").trim()
                    return if (t.length > 48) t.take(48) + "…" else t
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return ""
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val sameDay = now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYest = yesterday.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            isYest -> "Вчера"
            now.get(Calendar.YEAR) == cal.get(Calendar.YEAR) ->
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun setSelectMode(on: Boolean) {
        selectMode = on
        selected.clear()
        val btnNew = findViewById<View>(R.id.btnNew)
        val btnMode = findViewById<View>(R.id.btnSelectMode)
        val btnCancel = findViewById<View>(R.id.btnCancelSelect)
        val bar = findViewById<View>(R.id.selectionBar)
        btnNew.visibility = if (on) View.GONE else View.VISIBLE
        btnMode.visibility = if (on) View.GONE else View.VISIBLE
        btnCancel.visibility = if (on) View.VISIBLE else View.GONE
        if (animOn()) {
            // Crossfade the header buttons, slide the bar in/out
            for (v in if (on) listOf(btnCancel) else listOf(btnNew, btnMode)) {
                v.alpha = 0f
                v.animate().alpha(1f).setDuration(200).start()
            }
            if (on) {
                bar.visibility = View.VISIBLE
                bar.alpha = 0f
                bar.translationY = dp(56).toFloat()
                bar.animate().alpha(1f).translationY(0f).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            } else if (bar.visibility == View.VISIBLE) {
                bar.animate().alpha(0f).translationY(dp(56).toFloat()).setDuration(200)
                    .withEndAction { bar.visibility = View.GONE; bar.alpha = 1f; bar.translationY = 0f }.start()
            }
        } else {
            bar.visibility = if (on) View.VISIBLE else View.GONE
        }
        updateSelectedCount()
        animateList = true
        renderList()
    }

    private fun updateSelectedCount() {
        findViewById<TextView>(R.id.selectedCount).text = "Выбрано: ${selected.size}"
    }

    private fun renderList() {
        val list = findViewById<LinearLayout>(R.id.list)
        list.removeAllViews()
        val items: List<ChatItem> = if (filter.isEmpty()) {
            loadItems()
        } else {
            loadItems().mapNotNull { item ->
                val titleMatch = item.title.lowercase().contains(filter)
                val previewMatch = item.preview.lowercase().contains(filter)
                if (titleMatch || previewMatch) {
                    item
                } else {
                    val snippet = searchSnippet(item.id, filter)
                    if (snippet != null) item.copy(preview = snippet) else null
                }
            }
        }
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = if (filter.isNotEmpty()) "Ничего не найдено" else "Пока нет чатов"
                setTextColor(Th.fg(0xFF777777.toInt()))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
                if (animOn()) { alpha = 0f; animate().alpha(1f).setDuration(260).start() }
            })
            animateList = false
            return
        }
        for (item in items) {
            list.addView(makeRow(item))
        }
        if (animateList && animOn()) list.staggerChildrenIn(baseDelayMs = 20L, stepMs = 35L, limit = 12, dy = 16f)
        animateList = false
    }

    private fun makeRow(item: ChatItem): View {
        val isSel = selected.contains(item.id)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            minimumHeight = dp(64)
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
            background = getDrawable(if (isSel) R.drawable.bg_chat_row_card_selected else R.drawable.bg_chat_row_card)
            tag = item.id
        }

        if (selectMode) {
            val left = TextView(this).apply {
                text = if (isSel) Ico.glyph(context, R.drawable.ic_check, 20f, 0xFFFFFFFF.toInt()) else "○"
                setTextColor(if (isSel) 0xFFFFFFFF.toInt() else Th.fg(0xFF888888.toInt()))
                textSize = 18f
                gravity = Gravity.CENTER
                background = getDrawable(if (isSel) R.drawable.bg_settings_icon_accent else R.drawable.bg_settings_icon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            }
            if (item.id == popId && animOn()) {
                // Overshoot pop on the row that was just toggled
                left.scaleX = 0.5f; left.scaleY = 0.5f
                left.animate().scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
            }
            row.addView(left)
        } else {
            val left = ImageView(this).apply {
                setImageResource(R.drawable.ic_chat_bubble)
                setColorFilter(Th.fg(0xFFB39DFF.toInt()))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = getDrawable(R.drawable.bg_settings_icon)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            }
            row.addView(left)
        }

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        mid.addView(TextView(this).apply {
            text = item.title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        if (item.preview.isNotBlank()) {
            mid.addView(TextView(this).apply {
                text = item.preview
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 13f
                maxLines = 1
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(mid)

        row.addView(TextView(this).apply {
            text = formatTime(item.timeMs)
            setTextColor(Th.fg(0xFF777777.toInt()))
            textSize = 12f
            setPadding(dp(8), 0, 0, 0)
        })

        row.applyPressAnimation(PressAnimStyle.LIFT)
        row.setOnClickListener {
            if (selectMode) {
                if (selected.contains(item.id)) selected.remove(item.id) else selected.add(item.id)
                popId = item.id
                updateSelectedCount()
                animateList = false
                renderList()
                popId = null
            } else {
                setResult(RESULT_OPEN, Intent().putExtra(EXTRA_OPEN_CHAT_ID, item.id))
                finish()
            }
        }
        row.setOnLongClickListener {
            if (!selectMode) {
                setSelectMode(true)
                selected.add(item.id)
                popId = item.id
                updateSelectedCount()
                animateList = false
                renderList()
                popId = null
            }
            true
        }
        return row
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) return
        styledConfirmDialog(
            title = "Удалить чаты?",
            message = "Будет удалено: ${selected.size}",
            positiveText = "Удалить",
            destructive = true
        ) {
            val doDelete = {
                val idx = chatIndex()
                val newIdx = JSONArray()
                for (i in 0 until idx.length()) {
                    val o = idx.getJSONObject(i)
                    val id = o.getString("id")
                    if (selected.contains(id)) {
                        prefs.edit().remove("chat_${id}_messages").apply()
                    } else {
                        newIdx.put(o)
                    }
                }
                prefs.edit().putString("chats_index", newIdx.toString()).apply()
                val deletedCurrent = selected.contains(prefs.getString("current_chat_id", ""))
                selected.clear()
                setSelectMode(false)
                if (deletedCurrent) {
                    setResult(RESULT_NEW, Intent().putExtra(EXTRA_NEW_CHAT, true))
                    finish()
                }
            }
            if (animOn()) {
                // Slide the selected rows out before removing them
                val list = findViewById<LinearLayout>(R.id.list)
                var n = 0
                for (c in 0 until list.childCount) {
                    val v = list.getChildAt(c)
                    if ((v.tag as? String)?.let { it in selected } == true) {
                        v.animate().alpha(0f).translationX(dp(60).toFloat())
                            .setStartDelay(35L * n++).setDuration(200).start()
                    }
                }
                list.postDelayed({ doDelete() }, 200L + 35L * n)
            } else {
                doDelete()
            }
        }
    }
}
