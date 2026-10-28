package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TerminalActivity : NanoBaseActivity() {
    override val glassWindow: Boolean get() = false

    companion object {
        const val EXTRA_PENDING_COMMAND = "pending_command"
        private val API_KEY_PROVIDERS = ApiKeyGate.BUILTIN_PROVIDERS + "cloudflare_account"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var output: TextView
    private lateinit var outputScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var promptLabel: TextView
    private lateinit var root: File
    private lateinit var home: File
    private var currentDir: File = File("/")
    private val cmdHistory = mutableListOf<String>()

    private var pendingCommand: String? = null

    private var lastFetchedModels: List<String>? = null
    private var lastFetchedProviderUrl: String? = null
    private var lastFetchedKey: String? = null

    private val prefs by lazy { getSharedPreferences("nanoai", MODE_PRIVATE) }

    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = filesDir.resolve("NanoAI").apply { mkdirs() }
        home = root
        currentDir = home

        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Th.bg(0xFF000000.toInt()))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            clipChildren = true
            clipToPadding = true
        }
        setContentView(rootView)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(dp(10) + bars.left, dp(10) + bars.top, dp(10) + bars.right, dp(10) + maxOf(bars.bottom, ime.bottom))
            insets
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        head.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_arrow_back, 24f, Th.fg(0xFFFFFFFF.toInt()))
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 22f
            setPadding(dp(4), 0, dp(12), 0)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        })
head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_code_asset)
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(0); marginEnd = dp(8) }
        })
        head.addView(TextView(this).apply {
            text = "Терминал"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = "Сеансы"
            setTextColor(Th.fg(0xFF8FA8FF.toInt()))
            textSize = 14f
            setPadding(0, 0, dp(14), 0)
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { showSessionsDialog() }
        })
        head.addView(TextView(this).apply {
            text = "Очистить"
            setTextColor(Th.fg(0xFF7CFFB2.toInt()))
            textSize = 14f
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                styledConfirmDialog(
                    title = "Очистить вывод?",
                    message = "Весь вывод текущего сеанса терминала будет удалён без возможности восстановить.",
                    positiveText = "Очистить",
                    destructive = true
                ) { clearOutput() }
            }
        })
        rootView.addView(head)

        output = TextView(this).apply {
            setTextColor(Th.fg(0xFFDCDCDC.toInt()))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        outputScroll = ScrollView(this).apply {
            addView(output, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        rootView.addView(outputScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        promptLabel = TextView(this).apply {
            text = "~ $"
            setTextColor(Th.fg(0xFF7CFFB2.toInt()))
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, 0, dp(6), 0)
        }
        inputRow.addView(promptLabel)
        input = EditText(this).apply {
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            hint = "команда…"
            background = null
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    submit(); true
                } else false
            }
        }
        inputRow.addView(input)
        inputRow.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_send_arrow, 20f, Th.fg(0xFF7CFFB2.toInt()))
            setTextColor(Th.fg(0xFF7CFFB2.toInt()))
            textSize = 18f
            setPadding(dp(10), dp(4), dp(4), dp(4))
            applyPressAnimation(PressAnimStyle.BOUNCE)
            setOnClickListener { submit() }
        })
        rootView.addView(inputRow)

        restoreOrCreateSession()
        updatePrompt()

        val pending = intent.getStringExtra(EXTRA_PENDING_COMMAND)
        if (!pending.isNullOrBlank()) {
            printLine("")
            printLine("🤖 Команда предложена из чата:")
            printLine("  $pending")
            printLine("Выполнить? (y/n)")
            pendingCommand = pending
        }
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun trOut(s: String): String {
        if (s.isEmpty() || !Loc.isTranslating) return s
        val whole = Loc.t(s)
        if (whole != s || !s.contains('\n')) return whole
        return s.split("\n").joinToString("\n") { Loc.t(it) }
    }

    private fun printLine(raw: String) {
        val s = trOut(raw)
        output.append(if (output.text.isEmpty()) s else "\n$s")
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
        saveSession()
    }

    private fun clearOutput() {
        output.text = ""
    }

    private fun updatePrompt() {
        val rel = relativePath(currentDir)
        promptLabel.text = "$rel $"
    }

    private fun relativePath(f: File): String {
        val homePath = home.absolutePath
        val p = f.absolutePath
        return if (p == homePath) "~" else "~" + p.removePrefix(homePath)
    }

    private data class SessionMeta(val id: String, val name: String, val updatedAt: Long)

    private fun sessionsIndex(): List<SessionMeta> {
        val raw = prefs.getString("terminal_sessions_index", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SessionMeta(o.getString("id"), o.optString("name").ifBlank { "Сеанс" }, o.optLong("updatedAt"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveSessionsIndex(list: List<SessionMeta>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("name", m.name).put("updatedAt", m.updatedAt))
        }
        prefs.edit().putString("terminal_sessions_index", arr.toString()).apply()
    }

    private fun touchSessionMeta() {
        val list = sessionsIndex().toMutableList()
        val idx = list.indexOfFirst { it.id == sessionId }
        val name = list.getOrNull(idx)?.name ?: "Сеанс ${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())}"
        val updated = SessionMeta(sessionId, name, System.currentTimeMillis())
        if (idx >= 0) list[idx] = updated else list.add(updated)
        saveSessionsIndex(list)
    }

    private fun saveSession() {
        if (sessionId.isEmpty()) return
        val hist = JSONArray().apply { cmdHistory.forEach { put(it) } }
        prefs.edit()
            .putString("terminal_session_${sessionId}_output", output.text.toString())
            .putString("terminal_session_${sessionId}_history", hist.toString())
            .putString("terminal_session_${sessionId}_cwd", relativePath(currentDir))
            .apply()
        touchSessionMeta()
    }

    private fun loadSession(id: String) {
        sessionId = id
        prefs.edit().putString("terminal_current_session_id", id).apply()
        output.text = prefs.getString("terminal_session_${id}_output", "") ?: ""
        cmdHistory.clear()
        try {
            val hist = JSONArray(prefs.getString("terminal_session_${id}_history", "[]") ?: "[]")
            for (i in 0 until hist.length()) cmdHistory.add(hist.getString(i))
        } catch (_: Exception) {}
        val cwdRel = prefs.getString("terminal_session_${id}_cwd", "~") ?: "~"
        currentDir = if (cwdRel == "~") home else (resolve(cwdRel.removePrefix("~")) ?: home)
        updatePrompt()
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun newSession(name: String? = null) {
        val id = System.currentTimeMillis().toString()
        val list = sessionsIndex().toMutableList()
        list.add(SessionMeta(id, name?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Сеанс ${SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())}", System.currentTimeMillis()))
        saveSessionsIndex(list)
        sessionId = id
        prefs.edit().putString("terminal_current_session_id", id).apply()
        output.text = ""
        cmdHistory.clear()
        currentDir = home
        printLine("Yourbit AI Terminal — песочница приложения (${root.absolutePath}).")
        printLine("Наберите /help для списка команд.")
        updatePrompt()
        saveSession()
    }

    private fun deleteSession(id: String) {
        val list = sessionsIndex().filter { it.id != id }
        saveSessionsIndex(list)
        prefs.edit()
            .remove("terminal_session_${id}_output")
            .remove("terminal_session_${id}_history")
            .remove("terminal_session_${id}_cwd")
            .apply()
        if (id == sessionId) {
            val next = list.maxByOrNull { it.updatedAt }
            if (next != null) loadSession(next.id) else newSession()
        }
    }

    private fun restoreOrCreateSession() {
        val lastId = prefs.getString("terminal_current_session_id", null)
        val exists = lastId != null && sessionsIndex().any { it.id == lastId }
        if (exists) {
            loadSession(lastId!!)
        } else {
            newSession()
        }
    }

    private fun showSessionsDialog() {
        val sessions = sessionsIndex().sortedByDescending { it.updatedAt }
        val dialog = smoothDialog()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setGravity(Gravity.BOTTOM)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF16161C.toInt()))
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF3A3A45.toInt()))
                cornerRadius = dp(2).toFloat()
            }
        })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Сеансы терминала"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = if (sessions.isEmpty()) "Пока пусто" else "${sessions.size} " + pluralSessions(sessions.size)
            setTextColor(Th.fg(0xFF9A9AA5.toInt()))
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(titleCol)
        header.addView(TextView(this).apply {
            text = Ico.glyph(context, R.drawable.ic_close, 15f, Th.fg(0xFFCCCCCC.toInt()))
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 16f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF2A2A32.toInt()))
                cornerRadius = dp(18).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.5f).toInt()
            ).apply { topMargin = dp(14) }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll)

        if (sessions.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Сеансов пока нет — создайте первый ниже."
                setTextColor(Th.fg(0xFF777777.toInt()))
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(28), dp(12), dp(28))
            })
        }
        for (s in sessions) {
            list.addView(sessionRow(s, dialog))
        }

        val newBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF8AB4F8.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { newSession(); dialog.dismiss() }
        }
        newBtn.addView(TextView(this).apply {
            text = "+  Новый сеанс"
            setTextColor(Th.fg(0xFF101114.toInt()))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(newBtn)

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        enableSheetSwipe(dialog, root)
        dialog.show()
        Loc.translateTree(dialog.window?.decorView)
        applyThemeToDialog(dialog)
    }

    private fun sessionRow(s: SessionMeta, dialog: android.app.Dialog): View {
        val current = s.id == sessionId
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_chat_row_card)
            setPadding(dp(12), dp(12), dp(10), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                if (!current) loadSession(s.id)
                dialog.dismiss()
            }
        }

        row.addView(TextView(this).apply {
            text = ">_"
            setTextColor(if (current) Th.fg(0xFF101114.toInt()) else Th.fg(0xFF8AB4F8.toInt()))
            textSize = 14f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(if (current) Th.bg(0xFF8AB4F8.toInt()) else Th.bg(0xFF23232B.toInt()))
                cornerRadius = dp(10).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(this).apply {
            text = s.name
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
        })
        if (current) {
            nameRow.addView(TextView(this).apply {
                text = "текущий"
                setTextColor(Th.fg(0xFF101114.toInt()))
                textSize = 10.5f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = GradientDrawable().apply {
                    setColor(Th.bg(0xFF8AB4F8.toInt()))
                    cornerRadius = dp(20).toFloat()
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
            })
        }
        col.addView(nameRow)
        col.addView(TextView(this).apply {
            text = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(s.updatedAt))
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)

        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(Th.fg(0xFFE57373.toInt()))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Th.bg(0xFF241A1A.toInt()))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                styledConfirmDialog(
                    title = "Удалить сеанс?",
                    message = "«${s.name}» будет удалён без возможности восстановить.",
                    positiveText = "Удалить",
                    destructive = true
                ) {
                    deleteSession(s.id)
                    dialog.dismiss()
                    showSessionsDialog()
                }
            }
        })
        return row
    }

    private fun pluralSessions(n: Int): String {
        val mod10 = n % 10; val mod100 = n % 100
        return when {
            mod10 == 1 && mod100 != 11 -> "сеанс"
            mod10 in 2..4 && mod100 !in 12..14 -> "сеанса"
            else -> "сеансов"
        }
    }

    private fun submit() {
        val text = input.text.toString()
        if (text.isBlank()) return
        AchievementsStore.onTerminalCommand(this)
        input.text.clear()
        printLine("")
        printLine("${relativePath(currentDir)} $ $text")
        cmdHistory.add(text)

        val pending = pendingCommand
        if (pending != null) {
            when (text.trim().lowercase()) {
                "y", "yes", "да", "s", "si", "sí" -> {
                    pendingCommand = null
                    runCommand(pending)
                }
                "n", "no", "нет" -> {
                    pendingCommand = null
                    printLine("Отменено.")
                }
                else -> {
                    printLine("Введите y (выполнить) или n (отменить).")
                }
            }
            updatePrompt()
            saveSession()
            return
        }

        if (text.startsWith("/")) {
            handleSlashCommand(text)
        } else {
            runCommand(text)
        }
        updatePrompt()
        saveSession()
    }

    private fun tokenize(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (c in s) {
            when {
                quote != null -> {
                    if (c == quote) quote = null else cur.append(c)
                }
                c == '"' || c == '\'' -> quote = c
                c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun resolve(path: String): File? {
        val target = if (path.startsWith("/")) File(home, path.removePrefix("/")) else File(currentDir, path)
        val canon = try { File(target.canonicalPath) } catch (_: Exception) { return null }
        val canonRoot = try { root.canonicalPath } catch (_: Exception) { root.absolutePath }
        return if (canon.absolutePath == canonRoot || canon.absolutePath.startsWith("$canonRoot/")) canon else null
    }

    private fun runCommand(raw: String) {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return
        val cmd = tokens[0]
        val args = tokens.drop(1)
        try {
            when (cmd) {
                "pwd" -> printLine(currentDir.absolutePath)
                "ls" -> cmdLs(args)
                "cd" -> cmdCd(args)
                "cat" -> cmdCat(args)
                "echo" -> cmdEcho(args)
                "mkdir" -> cmdMkdir(args)
                "touch" -> cmdTouch(args)
                "rm" -> cmdRm(args)
                "mv" -> cmdMv(args)
                "cp" -> cmdCp(args)
                "clear" -> clearOutput()
                "date" -> printLine(SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.getDefault()).format(Date()))
                "whoami" -> printLine("nanoai")
                "uname" -> printLine("Yourbit AI Sandbox (Android)")
                "history" -> cmdHistory.forEachIndexed { i, h -> printLine("${i + 1}  $h") }
                "help" -> handleSlashCommand("/help")
                "exit" -> finish()
                else -> printLine("bash: $cmd: команда не найдена (см. /help)")
            }
        } catch (e: Exception) {
            printLine("Ошибка: ${e.message ?: e.toString()}")
        }
    }

    private fun cmdLs(args: List<String>) {
        val showAll = args.contains("-la") || args.contains("-a") || args.contains("-al")
        val pathArg = args.firstOrNull { !it.startsWith("-") }
        val dir = if (pathArg != null) resolve(pathArg) else currentDir
        if (dir == null) { printLine("ls: путь за пределами песочницы"); return }
        if (!dir.exists()) { printLine("ls: ${pathArg ?: "."}: нет такого файла или папки"); return }
        if (!dir.isDirectory) { printLine(dir.name); return }
        val entries = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        val visible = if (showAll) entries else entries.filter { !it.name.startsWith(".") }
        if (visible.isEmpty()) { printLine("(пусто)"); return }
        for (f in visible) {
            val marker = if (f.isDirectory) "/" else ""
            val size = if (f.isFile) "  ${f.length()}" + Loc.pick("б", "B") else ""
            printLine("${f.name}$marker$size")
        }
    }

    private fun cmdCd(args: List<String>) {
        val target = args.firstOrNull() ?: "~"
        val dest = if (target == "~") home else resolve(target)
        if (dest == null) { printLine("cd: путь за пределами песочницы"); return }
        if (!dest.exists() || !dest.isDirectory) { printLine("cd: $target: нет такой папки"); return }
        currentDir = dest
    }

    private fun cmdCat(args: List<String>) {
        if (args.isEmpty()) { printLine("Использование: cat <файл>"); return }
        for (a in args) {
            val f = resolve(a) ?: run { printLine("cat: $a: путь за пределами песочницы"); return }
            if (!f.exists() || !f.isFile) { printLine("cat: $a: нет такого файла"); continue }
            try {
                printLine(f.readText())
            } catch (e: Exception) {
                printLine("cat: $a: не удалось прочитать (${e.message})")
            }
        }
    }

    private fun cmdEcho(args: List<String>) {
        val redirIdx = args.indexOfFirst { it == ">" || it == ">>" }
        if (redirIdx == -1) {
            printLine(args.joinToString(" "))
            return
        }
        val text = args.take(redirIdx).joinToString(" ")
        val append = args[redirIdx] == ">>"
        val fname = args.getOrNull(redirIdx + 1)
        if (fname == null) { printLine("echo: не указан файл после ${args[redirIdx]}"); return }
        val f = resolve(fname) ?: run { printLine("echo: путь за пределами песочницы"); return }
        try {
            f.parentFile?.mkdirs()
            if (append) f.appendText(text + "\n") else f.writeText(text + "\n")
        } catch (e: Exception) {
            printLine("echo: не удалось записать (${e.message})")
        }
    }

    private fun cmdMkdir(args: List<String>) {
        val paths = args.filter { it != "-p" }
        if (paths.isEmpty()) { printLine("Использование: mkdir <папка>"); return }
        for (a in paths) {
            val f = resolve(a) ?: run { printLine("mkdir: $a: путь за пределами песочницы"); return }
            if (!f.mkdirs() && !f.exists()) printLine("mkdir: не удалось создать $a")
        }
    }

    private fun cmdTouch(args: List<String>) {
        if (args.isEmpty()) { printLine("Использование: touch <файл>"); return }
        for (a in args) {
            val f = resolve(a) ?: run { printLine("touch: $a: путь за пределами песочницы"); return }
            try {
                f.parentFile?.mkdirs()
                if (!f.exists()) f.createNewFile() else f.setLastModified(System.currentTimeMillis())
            } catch (e: Exception) {
                printLine("touch: $a: ${e.message}")
            }
        }
    }

    private fun cmdRm(args: List<String>) {
        val recursive = args.contains("-r") || args.contains("-rf") || args.contains("-fr")
        val paths = args.filter { !it.startsWith("-") }
        if (paths.isEmpty()) { printLine("Использование: rm [-r] <файл|папка>"); return }
        for (a in paths) {
            val f = resolve(a) ?: run { printLine("rm: $a: путь за пределами песочницы"); return }
            if (f.absolutePath == root.absolutePath) { printLine("rm: нельзя удалить корень песочницы"); continue }
            if (!f.exists()) { printLine("rm: $a: нет такого файла"); continue }
            val ok = if (f.isDirectory) {
                if (!recursive) { printLine("rm: $a: это папка (нужен -r)"); continue }
                f.deleteRecursively()
            } else f.delete()
            if (!ok) printLine("rm: не удалось удалить $a")
        }
    }

    private fun cmdMv(args: List<String>) {
        if (args.size != 2) { printLine("Использование: mv <откуда> <куда>"); return }
        val src = resolve(args[0]) ?: run { printLine("mv: путь за пределами песочницы"); return }
        val dst = resolve(args[1]) ?: run { printLine("mv: путь за пределами песочницы"); return }
        if (!src.exists()) { printLine("mv: ${args[0]}: нет такого файла"); return }
        val realDst = if (dst.isDirectory) File(dst, src.name) else dst
        if (!src.renameTo(realDst)) printLine("mv: не удалось переместить")
    }

    private fun cmdCp(args: List<String>) {
        if (args.size != 2) { printLine("Использование: cp <откуда> <куда>"); return }
        val src = resolve(args[0]) ?: run { printLine("cp: путь за пределами песочницы"); return }
        val dst = resolve(args[1]) ?: run { printLine("cp: путь за пределами песочницы"); return }
        if (!src.exists() || !src.isFile) { printLine("cp: ${args[0]}: копирование поддерживается только для файлов"); return }
        val realDst = if (dst.isDirectory) File(dst, src.name) else dst
        try {
            src.copyTo(realDst, overwrite = true)
        } catch (e: Exception) {
            printLine("cp: не удалось скопировать (${e.message})")
        }
    }

    private fun handleSlashCommand(raw: String) {
        val tokens = tokenize(raw)
        val cmd = tokens[0].lowercase()
        val args = tokens.drop(1)
        when (cmd) {
            "/help" -> printHelp()
            "/clear" -> clearOutput()
            "/addapikeys" -> cmdAddApiKeys(args)
            "/addmodels" -> cmdAddModels(args)
            "/syncmodels" -> cmdSyncModels()
            "/logs" -> cmdLogs(args)
            "/sessions" -> cmdSessions(args)
            "/devmenu" -> {
                startActivity(Intent(this, DevMenuActivity::class.java))
                printLine("Открываю меню разработчика…")
            }
            "/ai" -> cmdAi(args.joinToString(" "))
            else -> printLine("Неизвестная команда: $cmd (см. /help)")
        }
    }

    private fun cmdSyncModels() {
        printLine("Проверяю сервер моделей…")
        scope.launch {
            val report = withContext(Dispatchers.IO) { BuiltinModels.syncNow(applicationContext) }
            printLine(report)
        }
    }

    private fun printHelp() {
        printLine(
            "Команды файловой системы (в песочнице Yourbit AI):\n" +
                "  ls [-la] [путь]      cd <путь>          pwd\n" +
                "  cat <файл>           echo <текст> [> файл | >> файл]\n" +
                "  mkdir <папка>        touch <файл>       rm [-r] <путь>\n" +
                "  mv <откуда> <куда>   cp <откуда> <куда> clear\n" +
                "  date  whoami  uname  history\n\n" +
                "Служебные команды:\n" +
                "  /help                          — эта справка\n" +
                "  /addapikeys                    — статус всех API-ключей\n" +
                "  /addapikeys <провайдер> <ключ>  — сохранить ключ (openrouter, hf, groq,\n" +
                "                                     gemini, cloudflare, cloudflare_account,\n" +
                "                                     llm7, pollinations, mistral, cohere, openai,\n" +
                "                                     qwen, deepseek, anthropic, nvidia);\n" +
                "                                     сохраняет ваш ключ для прямых запросов\n" +
                "  /addapikeys <провайдер> clear   — удалить ключ провайдера\n" +
                "  /addapikeys p=KEY p2=KEY        — несколько ключей одной командой\n" +
                "  /addmodels <url> <ключ>         — получить список моделей провайдера\n" +
                "  /addmodels add <id|номер>       — добавить модель из последнего списка\n" +
                "  /syncmodels                     — обновить встроенные модели с сервера и показать диагностику\n" +
                "  /logs [число]                   — показать последние логи; /logs clear — очистить\n" +
                "  /sessions                       — список сеансов терминала (или кнопка «Сеансы»)\n" +
                "  /sessions new [имя]             — новый сеанс\n" +
                "  /sessions load <id>             — переключиться на сеанс\n" +
                "  /sessions delete <id>           — удалить сеанс\n" +
                "  /devmenu                        — открыть меню продвинутых настроек\n" +
                "  /ai <запрос>                    — ИИ предложит команду; выполнится только\n" +
                "                                     после вашего подтверждения (y/n)\n" +
                "  /clear                          — очистить экран"
        )
    }


    private fun cmdAddApiKeys(args: List<String>) {
        if (args.isEmpty()) {
            printLine("Статус API-ключей:")
            for (p in API_KEY_PROVIDERS) {
                val has = !prefs.getString(p, "").isNullOrBlank()
                printLine("  ${if (has) "●" else "○"} $p — ${if (has) "сохранён" else "не задан"}")
            }
            printLine("Можно задать несколько сразу:")
            printLine("  /addapikeys groq=KEY hf=KEY gemini=KEY")
            printLine("  /addapikeys groq KEY hf KEY")
            return
        }

        val updates = linkedMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            val eq = token.indexOf('=')
            val colon = token.indexOf(':')
            val sep = when {
                eq > 0 -> eq
                colon > 0 && !token.startsWith("http", true) -> colon
                else -> -1
            }
            if (sep > 0) {
                val provider = token.substring(0, sep).lowercase()
                val value = token.substring(sep + 1)
                if (provider in API_KEY_PROVIDERS) {
                    updates[provider] = value
                } else {
                    printLine("Неизвестный провайдер: $provider")
                }
                i++
                continue
            }
            if (token.lowercase() !in API_KEY_PROVIDERS) {
                printLine("Ожидался провайдер, например groq, hf, gemini: $token")
                i++
                continue
            }
            val provider = token.lowercase()
            val value = args.getOrNull(i + 1)
            if (value == null) {
                printLine("$provider: ключ не указан")
                i++
            } else {
                updates[provider] = value
                i += 2
            }
        }

        if (updates.isEmpty()) {
            printLine("Ничего не изменено. Пример: /addapikeys groq=KEY hf=KEY")
            return
        }

        val editor = prefs.edit()
        for ((provider, valueRaw) in updates) {
            val value = valueRaw.trim()
            if (value.equals("clear", ignoreCase = true)) {
                editor.putString(provider, "")
                printLine("✓ $provider: ключ удалён")
            } else {
                editor.putString(provider, value)
                printLine("✓ $provider: ключ сохранён")
            }
        }
        editor.apply()
        NanoLog.log(this, "API_KEYS updated providers=${updates.keys.joinToString(",")}")
        printLine("Готово. Изменений: ${updates.size}.")
    }


    private fun cmdSessions(args: List<String>) {
        val sub = args.firstOrNull()?.lowercase()
        when (sub) {
            null, "list" -> {
                val sessions = sessionsIndex().sortedByDescending { it.updatedAt }
                if (sessions.isEmpty()) { printLine("Сеансов пока нет."); return }
                printLine("Сеансы (текущий отмечен ●):")
                for (s in sessions) {
                    val mark = if (s.id == sessionId) "●" else "○"
                    val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(s.updatedAt))
                    printLine("  $mark ${s.id}  \"${s.name}\"  ($date)")
                }
                printLine("Откройте список кнопкой «Сеансы» вверху, либо: /sessions new [имя] · /sessions load <id> · /sessions delete <id>")
            }
            "new" -> newSession(args.drop(1).joinToString(" ").ifBlank { null })
            "load", "switch" -> {
                val id = args.getOrNull(1)
                if (id == null) { printLine("Использование: /sessions load <id>"); return }
                if (sessionsIndex().none { it.id == id }) { printLine("Сеанс не найден: $id"); return }
                loadSession(id)
                printLine("✓ Переключено на сеанс $id")
            }
            "delete", "rm" -> {
                val id = args.getOrNull(1)
                if (id == null) { printLine("Использование: /sessions delete <id>"); return }
                if (sessionsIndex().none { it.id == id }) { printLine("Сеанс не найден: $id"); return }
                deleteSession(id)
                printLine("✓ Сеанс удалён: $id")
            }
            else -> printLine("Использование: /sessions [list|new [имя]|load <id>|delete <id>]")
        }
    }

    private fun cmdLogs(args: List<String>) {
        if (args.firstOrNull()?.equals("clear", true) == true) {
            NanoLog.clear(this)
            printLine("✓ Логи очищены.")
            return
        }
        val count = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, 500) ?: 120
        val logs = NanoLog.read(this, count)
        if (logs.isBlank()) {
            printLine("Лог пока пуст.")
            return
        }
        printLine("— Yourbit AI logs (последние $count строк) —")
        printLine(logs)
    }


    private fun cmdAddModels(args: List<String>) {
        if (args.isEmpty()) {
            printLine("Использование:")
            printLine("  /addmodels <providerUrl> [ключ]   — получить список моделей")
            printLine("  /addmodels add <id|номер>         — добавить модель из списка")
            return
        }
        if (args[0].equals("add", ignoreCase = true)) {
            val list = lastFetchedModels
            val providerUrl = lastFetchedProviderUrl
            val key = lastFetchedKey
            if (list == null || providerUrl == null) {
                printLine("Сначала получите список: /addmodels <providerUrl> [ключ]")
                return
            }
            val sel = args.getOrNull(1)
            if (sel == null) { printLine("Использование: /addmodels add <id|номер>"); return }
            val id = sel.toIntOrNull()?.let { list.getOrNull(it - 1) } ?: sel.takeIf { it in list } ?: sel
            ModelsStore.add(this, CustomModel(id = id, name = id, providerUrl = providerUrl, apiKey = key ?: "", source = "link"))
            printLine("✅ Модель \"$id\" добавлена. Она появится в списке моделей (Настройки → Параметры для разработчиков → Модели).")
            return
        }
        val providerUrl = args[0]
        val key = args.drop(1).joinToString(" ").trim().ifBlank { ProvidersStore.keyForUrl(this, providerUrl) }
        printLine("Запрашиваю список моделей у $providerUrl…")
        scope.launch {
            try {
                val models = withContext(Dispatchers.IO) { ApiClient.listModels(providerUrl, key) }
                if (models.isEmpty()) {
                    printLine("Провайдер не вернул ни одной модели.")
                    return@launch
                }
                lastFetchedModels = models
                lastFetchedProviderUrl = providerUrl
                lastFetchedKey = key
                printLine("Найдено моделей: ${models.size}")
                models.forEachIndexed { i, id -> printLine("  ${i + 1}. $id") }
                printLine("Добавить: /addmodels add <номер>")
            } catch (e: Exception) {
                printLine("Не удалось получить список моделей: ${e.message}")
            }
        }
    }

    private fun cmdAi(prompt: String) {
        if (prompt.isBlank()) { printLine("Использование: /ai <что нужно сделать>"); return }
        printLine("🤖 Думаю над командой…")
        scope.launch {
            try {
                val suggestion = withContext(Dispatchers.IO) { suggestShellCommand(prompt) }
                if (suggestion == null || suggestion.equals("UNSUPPORTED", ignoreCase = true)) {
                    printLine("ИИ не смог подобрать подходящую команду для песочницы.")
                    return@launch
                }
                printLine("Предложенная команда:")
                printLine("  $suggestion")
                printLine("Выполнить? (y/n)")
                pendingCommand = suggestion
            } catch (e: Exception) {
                printLine("Ошибка запроса к ИИ: ${e.message}")
            }
        }
    }

    private suspend fun suggestShellCommand(userPrompt: String): String? {
        val provider = "groq"
        val model = "openai/gpt-oss-120b"
        val apiKey = prefs.getString(provider, "") ?: ""
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val system = "You are a command generator for the built-in sandboxed terminal of an Android app. " +
            "Available commands: ls, cd, pwd, cat, echo, mkdir, touch, rm, mv, cp, clear, date, whoami, uname, history. " +
            "The file system is limited to the app's internal folder. " +
            "Reply with EXACTLY one command, with no markdown, quotes or explanations. " +
            "Today is " + java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH)) + ". " +
            "If the request cannot be expressed with these commands, reply exactly: UNSUPPORTED"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val raw = ApiClient.chat(provider, model, apiKey, messages, deviceId, maxTokens = 200)
        return raw.trim().removePrefix("```").removeSuffix("```").trim().lines().firstOrNull()?.trim()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
