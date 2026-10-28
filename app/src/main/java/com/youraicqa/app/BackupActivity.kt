package com.youraicqa.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.youraicqa.app.linkimporter.MediaDownloadHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true

    private val importReq = 7401
    private lateinit var sessionsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        findViewById<View>(R.id.btnBack).apply {
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { finish() }
        }

        val container = findViewById<LinearLayout>(R.id.backupContainer)

        container.addView(sectionTitle("Экспорт"))
        container.addView(card(listOf(
            actionRow("Экспортировать чаты", "Выберите нужные чаты, формат (.nanobackup, .md, .json) и параметры. Без ключей API") { startExportChats() },
            actionRow("Экспортировать аккаунт", "Чаты + ключи API + модели + плагины + оформление + файлы Библиотеки") { startExportAccount() }
        )))

        container.addView(sectionTitle("Импорт"))
        container.addView(card(listOf(
            actionRow("Импортировать из файла", "Принимает .nanobackup и .json. Чаты — выберите нужные и режим при совпадении. Аккаунт — заменит всё текущее") { pickImportFile() }
        )))

        container.addView(sectionTitle("Сессии"))
        sessionsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(sessionsContainer)
        refreshSessions()

        container.addView(sectionTitle("Сохранение при удалении приложения"))
        container.addView(infoCard(
            "Автоматическое резервное копирование Android включено: если в настройках " +
            "телефона (Настройки → Система → Резервное копирование) включена резервная копия " +
            "и вы вошли в Google-аккаунт, при переустановке Yourbit AI чаты, ключи API, плагины и " +
            "оформление восстановятся сами.\n\n" +
            "Это зависит от настроек телефона, поэтому перед удалением приложения надёжнее " +
            "всего сделать ручной экспорт аккаунта выше — он гарантированно сработает на " +
            "любом устройстве, если сохранить файл и пароль."
        ))
    }

    private val bkPrefs get() = getSharedPreferences("nanoai", MODE_PRIVATE)

    private fun startExportChats() {
        val allChats = BackupManager.listLocalChats(this)
        if (allChats.isEmpty()) {
            Toast.makeText(this, Loc.t("Нет ни одного чата для экспорта"), Toast.LENGTH_SHORT).show()
            return
        }
        chatPickerDialog(
            title = "Какие чаты экспортировать?",
            chats = allChats
        ) { selectedIds ->
            exportOptionsDialog(selectedIds.size) { o -> runChatsExport(selectedIds, o) }
        }
    }

    private class ExportOpts(
        var format: Int, var lastIdx: Int, var withTs: Boolean, var share: Boolean, var name: String
    )

    private val lastNValues = intArrayOf(0, 100, 30, 10)

    private fun exportOptionsDialog(chatCount: Int, onDone: (ExportOpts) -> Unit) {
        val o = ExportOpts(
            format = bkPrefs.getInt("bk_exp_format", 0).coerceIn(0, 2),
            lastIdx = bkPrefs.getInt("bk_exp_last", 0).coerceIn(0, lastNValues.size - 1),
            withTs = bkPrefs.getBoolean("bk_exp_ts", true),
            share = bkPrefs.getBoolean("bk_exp_share", false),
            name = ""
        )
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(hintText(Loc.t("Выбрано чатов: $chatCount")))

        col.addView(optionsTitle("Формат файла"))
        val warn = hintText("")
        fun updateWarn() {
            warn.text = Loc.t(if (o.format == 0) "Файл защищён паролем (AES-256)."
                else "Файл без шифрования: любой, у кого он окажется, сможет прочитать переписку.")
            warn.setTextColor(Th.fg(if (o.format == 0) 0xFF8A8A92.toInt() else 0xFFFFB74D.toInt()))
        }
        col.addView(radioGroup(listOf(
            "Зашифрованный (.nanobackup) — можно импортировать обратно",
            "Читаемый текст (.md) — только для чтения",
            "JSON (.json) — без пароля, можно импортировать обратно"
        ), o.format) { o.format = it; updateWarn() })
        col.addView(warn)
        updateWarn()

        col.addView(optionsTitle("Сколько сообщений брать из каждого чата"))
        col.addView(radioGroup(listOf(
            "Все сообщения", "Последние 100", "Последние 30", "Последние 10"
        ), o.lastIdx) { o.lastIdx = it })

        col.addView(optionsTitle("Дополнительно"))
        col.addView(optionCheck("Время сообщений в читаемом тексте (.md)", o.withTs) { o.withTs = it })
        col.addView(optionCheck("Открыть «Поделиться» вместо сохранения в Загрузки", o.share) { o.share = it })

        col.addView(optionsTitle("Имя файла (необязательно)"))
        val nameField = plainField("Например: мои-чаты")
        col.addView(nameField)

        styledPanelDialog(
            "Параметры экспорта", col,
            listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Экспортировать") {
                    o.name = nameField.text.toString()
                    bkPrefs.edit()
                        .putInt("bk_exp_format", o.format).putInt("bk_exp_last", o.lastIdx)
                        .putBoolean("bk_exp_ts", o.withTs).putBoolean("bk_exp_share", o.share).apply()
                    onDone(o)
                }
            )
        )
    }

    private fun runChatsExport(ids: Set<String>, o: ExportOpts) {
        val lastN = lastNValues[o.lastIdx]
        val custom = o.name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".nanobackup")
            .removeSuffix(".md").removeSuffix(".json")
        fun go(password: String?) {
            when (o.format) {
                0 -> exportAsync(custom.ifBlank { null }, "Yourbit AI-chats", "nanobackup", o.share) {
                    BackupManager.encrypt(BackupManager.collectChats(this, ids, lastN), password!!) to emptyList()
                }
                1 -> exportAsync(custom.ifBlank { null }, "Yourbit AI-chats", "md", o.share) {
                    BackupManager.chatsToMarkdown(BackupManager.collectChats(this, ids, lastN), o.withTs)
                        .toByteArray(Charsets.UTF_8) to emptyList()
                }
                else -> exportAsync(custom.ifBlank { null }, "Yourbit AI-chats", "json", o.share) {
                    BackupManager.collectChats(this, ids, lastN).toString(2).toByteArray(Charsets.UTF_8) to emptyList()
                }
            }
        }
        if (o.format == 0) promptNewPassword("Пароль для экспорта чатов") { go(it) } else go(null)
    }

    private fun startExportAccount() {
        styledConfirmDialog(
            title = "Экспортировать весь аккаунт?",
            message = "Файл будет содержать чаты, ключи API, модели, плагины, оформление и файлы " +
                "из «Библиотеки». Храните его и пароль так же бережно, как сами ключи API.",
            positiveText = "Продолжить"
        ) {
            promptNewPassword("Пароль для экспорта аккаунта") { password ->
                exportAsync(null, "Yourbit AI-account", "nanobackup", false) {
                    val json = BackupManager.collectAccount(this)
                    BackupManager.encrypt(json, password) to BackupManager.skippedLibraryFiles(json)
                }
            }
        }
    }

    private fun exportAsync(
        customName: String?, prefix: String, ext: String, share: Boolean,
        produce: () -> Pair<ByteArray, List<String>>
    ) {
        val dialog = progressDialog("Готовим файл экспорта…")
        baseScope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val (bytes, skippedFiles) = produce()
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    val fname = (customName ?: "$prefix-$stamp") + ".$ext"
                    val file = File(cacheDir, fname)
                    file.writeBytes(bytes)
                    val uri = if (share) androidx.core.content.FileProvider.getUriForFile(
                        this@BackupActivity, "$packageName.fileprovider", file
                    ) else MediaDownloadHelper.saveToDownloads(this@BackupActivity, file)
                    Triple(fname, uri, skippedFiles)
                }
                val (name, uri, skipped) = res
                if (uri == null) {
                    Toast.makeText(this@BackupActivity, Loc.t("Не удалось сохранить файл"), Toast.LENGTH_LONG).show()
                } else if (share) {
                    val mime = when (ext) { "md" -> "text/markdown"; "json" -> "application/json"; else -> "application/octet-stream" }
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, Loc.t("Поделиться файлом экспорта")))
                } else if (skipped.isEmpty()) {
                    Toast.makeText(this@BackupActivity, Loc.t("Сохранено в Загрузки: $name"), Toast.LENGTH_LONG).show()
                } else {
                    val list = skipped.joinToString(", ")
                    Toast.makeText(
                        this@BackupActivity,
                        Loc.t("Сохранено: $name. Не поместились в бэкап (слишком большие): $list"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@BackupActivity, Loc.t("Экспорт: ${e.message}"), Toast.LENGTH_LONG).show()
            } finally {
                dialog.dismiss()
            }
        }
    }

    private fun pickImportFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(i, "Выбрать файл резервной копии"), importReq)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != importReq || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Не удалось прочитать файл")
            // Readable .json export of chats has no password; everything else is encrypted.
            if (BackupManager.isPlainJson(bytes)) {
                val json = BackupManager.parsePlain(bytes)
                if (json.optString("type") != "chats") {
                    Toast.makeText(this, Loc.t("Файл без шифрования может содержать только чаты"), Toast.LENGTH_LONG).show()
                    return
                }
                handleDecrypted(json)
                return
            }
            if (looksLikeMarkdown(bytes)) {
                Toast.makeText(this, Loc.t("Читаемый текст (.md) нельзя импортировать — выберите .nanobackup или .json"), Toast.LENGTH_LONG).show()
                return
            }
            promptExistingPassword("Пароль от файла резервной копии") { password ->
                val dialog = progressDialog("Проверяем пароль и читаем файл…")
                baseScope.launch {
                    try {
                        val json = withContext(Dispatchers.IO) { BackupManager.decrypt(bytes, password) }
                        dialog.dismiss()
                        handleDecrypted(json)
                    } catch (e: BackupManager.WrongPasswordException) {
                        dialog.dismiss()
                        Toast.makeText(this@BackupActivity, e.message, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        dialog.dismiss()
                        Toast.makeText(this@BackupActivity, Loc.t("Импорт: ${e.message}"), Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Импорт: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }

    private fun looksLikeMarkdown(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == '#'.code.toByte() && bytes[1] == ' '.code.toByte()

    private fun handleDecrypted(json: JSONObject) {
        when (json.optString("type")) {
            "chats" -> {
                val chatsInFile = BackupManager.describeChatsInBackup(json)
                if (chatsInFile.isEmpty()) {
                    Toast.makeText(this, Loc.t("В файле нет ни одного чата"), Toast.LENGTH_SHORT).show()
                } else {
                    chatPickerDialog(
                        title = "Какие чаты импортировать?",
                        chats = chatsInFile
                    ) { selectedIds ->
                        importOptionsDialog { opts ->
                            val r = BackupManager.importChats(this, json, selectedIds, opts)
                            Toast.makeText(
                                this,
                                Loc.t("Импорт чатов: добавлено ${r.added}, заменено ${r.replaced}, пропущено ${r.skipped}"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            "account" -> verifyAndImportAccount(json)
            else -> Toast.makeText(this, Loc.t("Неизвестный тип файла резервной копии"), Toast.LENGTH_LONG).show()
        }
    }

    private fun importOptionsDialog(onDone: (BackupManager.ImportOptions) -> Unit) {
        var mode = bkPrefs.getInt("bk_imp_mode", 0).coerceIn(0, 2)
        var mark = bkPrefs.getBoolean("bk_imp_mark", false)
        var onTop = bkPrefs.getBoolean("bk_imp_top", false)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(optionsTitle("Если чат с таким ID уже есть"))
        col.addView(radioGroup(listOf(
            "Добавить как новый чат",
            "Пропустить уже существующие",
            "Заменить существующие"
        ), mode) { mode = it })
        col.addView(hintText(Loc.t("Существующим считается чат с тем же ID — например, при повторном импорте того же файла. «Заменить» перезаписывает его сообщения и название.")))
        col.addView(optionsTitle("Дополнительно"))
        col.addView(optionCheck("Добавить к названию пометку «(импорт)»", mark) { mark = it })
        col.addView(optionCheck("Поставить импортированные чаты в начало списка", onTop) { onTop = it })
        styledPanelDialog(
            "Параметры импорта", col,
            listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Импортировать") {
                    bkPrefs.edit().putInt("bk_imp_mode", mode).putBoolean("bk_imp_mark", mark)
                        .putBoolean("bk_imp_top", onTop).apply()
                    onDone(BackupManager.ImportOptions(BackupManager.ImportMode.values()[mode], mark, onTop))
                }
            )
        )
    }

    private fun verifyAndImportAccount(json: JSONObject) {
        val d = progressDialog("Проверяем аккаунт…")
        baseScope.launch {
            val r = withContext(Dispatchers.IO) { Cloud.checkBackup(json) }
            d.dismiss()
            when (r.status) {
                "deleted" -> styledConfirmDialog(
                    title = "Аккаунт удалён",
                    message = "Этот аккаунт был удалён владельцем. Восстановить его из резервной копии нельзя.",
                    positiveText = "Понятно"
                ) {}
                "denied" -> styledConfirmDialog(
                    title = "Резервная копия устарела",
                    message = "Владелец завершил все сессии этого аккаунта, поэтому эта копия больше не даёт доступа. " +
                        "Используйте более свежий экспорт.",
                    positiveText = "Понятно"
                ) {}
                else -> continueAccountImport(json)
            }
        }
    }

    private fun continueAccountImport(json: JSONObject) {
                if (SessionsManager.isThisDeviceRevokedIn(this, json)) {
                    styledConfirmDialog(
                        title = "Доступ к аккаунту отключён",
                        message = "Владелец аккаунта отключил это устройство в разделе «Сессии». " +
                            "Импорт этого аккаунта на нём заблокирован.",
                        positiveText = "Понятно"
                    ) {}
                    return
                }
                styledConfirmDialog(
                    title = "Заменить весь аккаунт?",
                    message = "Текущие чаты, ключи API, плагины, оформление и файлы Библиотеки " +
                        "будут заменены содержимым файла. Это действие необратимо.",
                    positiveText = "Заменить",
                    destructive = true
                ) {
                    val dialog = progressDialog("Восстанавливаем аккаунт…")
                    baseScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                BackupManager.importAccount(this@BackupActivity, json)
                                Cloud.syncBlocking(this@BackupActivity)
                            }
                            dialog.dismiss()
                            Toast.makeText(this@BackupActivity, Loc.t("Аккаунт восстановлен. Перезапустите приложение"), Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            dialog.dismiss()
                            Toast.makeText(this@BackupActivity, Loc.t("Импорт аккаунта: ${e.message}"), Toast.LENGTH_LONG).show()
                        }
                    }
                }
    }

    private fun refreshSessions() {
        SessionsManager.registerCurrentSession(this)
        renderSessions(Cloud.cachedSessions(this), null)
        baseScope.launch {
            val r = withContext(Dispatchers.IO) { Cloud.syncBlocking(this@BackupActivity) }
            if (Cloud.blocked(this@BackupActivity) != null) { Cloud.showBlockedDialog(this@BackupActivity); return@launch }
            renderSessions(if (r.ok) r.sessions.orEmpty() else Cloud.cachedSessions(this@BackupActivity), r.ok)
        }
    }

    private fun renderSessions(cloudList: List<Cloud.CloudSession>, online: Boolean?) {
        sessionsContainer.removeAllViews()
        val myId = SessionsManager.mySessionId(this)
        val fmt = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
        val list = cloudList.ifEmpty {
            SessionsManager.listSessions(this).map { Cloud.CloudSession(it.id, it.label, it.firstSeenAt, it.lastSeenAt) }
        }.sortedByDescending { it.lastSeenAt }

        val rows = list.map { s ->
            val isMine = s.id == myId
            sessionRow(
                label = s.label + if (isMine) " (это устройство)" else "",
                subtitle = "Последняя активность: ${fmt.format(Date(s.lastSeenAt))}",
                showDisconnect = !isMine && online == true
            ) {
                styledConfirmDialog(
                    title = "Отключить устройство?",
                    message = "«${s.label}» будет отключено сразу, как только оно выйдет в сеть: доступ к аккаунту " +
                        "закроется, а данные аккаунта на нём будут стёрты. Если у постороннего есть файл резервной " +
                        "копии — используйте «Завершить все остальные сессии».",
                    positiveText = "Отключить",
                    destructive = true
                ) {
                    val d = progressDialog("Отключаем…")
                    baseScope.launch {
                        val r = withContext(Dispatchers.IO) { Cloud.revokeSession(this@BackupActivity, s.id) }
                        d.dismiss()
                        if (r.ok) { SessionsManager.revoke(this@BackupActivity, s.id); toastRes("Устройство отключено") }
                        else toastRes(cloudError(r))
                        refreshSessions()
                    }
                }
            }
        }
        sessionsContainer.addView(card(rows.ifEmpty {
            listOf(TextView(this).apply {
                text = "Пока нет ни одной известной сессии."
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 13f
                setPadding(0, dp(10), 0, dp(10))
            })
        }))

        val status = when (online) {
            null -> "Синхронизация с облаком…"
            true -> "Облако: синхронизировано"
            false -> {
                val last = Cloud.lastSyncAt(this)
                "Нет связи с облаком — показаны последние известные данные" + if (last > 0) " (${fmt.format(Date(last))})" else ""
            }
        }
        sessionsContainer.addView(TextView(this).apply {
            text = status
            setTextColor(Th.fg(if (online == false) 0xFFFFB74D.toInt() else 0xFF8A8A92.toInt()))
            textSize = 12f
            setPadding(dp(4), dp(6), dp(4), dp(8))
        })

        val disabled = online != true
        val signOut = actionRow("Завершить все остальные сессии",
            "Отключает все другие устройства и делает недействительными старые резервные копии") {
            if (disabled) { toastRes("Нужна связь с облаком"); return@actionRow }
            styledConfirmDialog(
                title = "Завершить все остальные сессии?",
                message = "Все остальные устройства потеряют доступ и сотрут данные аккаунта при следующем выходе в сеть. " +
                    "Все ранее сделанные файлы экспорта аккаунта перестанут восстанавливаться — после этого сделайте новый экспорт.",
                positiveText = "Завершить",
                destructive = true
            ) {
                val d = progressDialog("Завершаем сессии…")
                baseScope.launch {
                    val r = withContext(Dispatchers.IO) { Cloud.signOutOthers(this@BackupActivity) }
                    d.dismiss()
                    toastRes(if (r.ok) "Остальные сессии завершены" else cloudError(r))
                    refreshSessions()
                }
            }
        }
        val delete = actionRow("Удалить аккаунт",
            "Удаляет аккаунт в облаке, отключает все сессии и блокирует любые резервные копии") { startDeleteAccount() }
        ((delete as LinearLayout).getChildAt(0) as TextView).setTextColor(Th.fg(0xFFFF6B5B.toInt()))
        sessionsContainer.addView(card(listOf(signOut, delete)))

        sessionsContainer.addView(infoCard(
            "Сессии хранятся в облаке (Supabase) и синхронизируются при каждом запуске приложения. «Отключить» закрывает " +
            "доступ конкретному устройству. Если аккаунт получил посторонний (например, файл резервной копии) — " +
            "«Завершить все остальные сессии»: это меняет ключ доступа, и старые файлы экспорта перестают работать. " +
            "Удаление аккаунта необратимо: резервные копии удалённого аккаунта восстановить нельзя."
        ))
    }

    private fun toastRes(msg: String) = Toast.makeText(this, Loc.t(msg), Toast.LENGTH_SHORT).show()

    private fun cloudError(r: Cloud.Result): String = when {
        r.status == "offline" -> "Нет связи с облаком"
        r.status == "denied" || r.status == "revoked" || r.status == "deleted" -> "Доступ к аккаунту закрыт"
        r.status.startsWith("http_") -> "Облако ответило ошибкой (код ${r.status.removePrefix("http_")})"
        else -> "Ошибка облака: ${r.status}"
    }

    private fun startDeleteAccount() {
        styledConfirmDialog(
            title = "Удалить аккаунт?",
            message = "Аккаунт будет удалён в облаке: все сессии (включая это устройство) завершатся, а резервные копии " +
                "этого аккаунта больше нельзя будет восстановить. Данные на этом устройстве (чаты, ключи, модели, плагины, " +
                "файлы) будут стёрты. Это действие необратимо.",
            positiveText = "Удалить",
            destructive = true
        ) {
            val d = progressDialog("Удаляем аккаунт…")
            baseScope.launch {
                val r = withContext(Dispatchers.IO) { Cloud.deleteAccountRemote(this@BackupActivity) }
                if (r.status == "deleted") {
                    withContext(Dispatchers.IO) { Cloud.wipeLocalAccount(this@BackupActivity) }
                    d.dismiss()
                    Cloud.restartApp(this@BackupActivity)
                } else {
                    d.dismiss()
                    // The cloud could not confirm the deletion: let the person still erase this device
                    styledConfirmDialog(
                        title = "Не удалось удалить аккаунт в облаке",
                        message = Loc.t(cloudError(r)) + "\n\n" + Loc.t("Стереть данные аккаунта только на этом устройстве? В облаке аккаунт останется, пока удаление не удастся."),
                        positiveText = "Стереть на устройстве",
                        destructive = true
                    ) {
                        baseScope.launch {
                            withContext(Dispatchers.IO) { Cloud.wipeLocalAccount(this@BackupActivity) }
                            Cloud.restartApp(this@BackupActivity)
                        }
                    }
                }
            }
        }
    }

    private fun sessionRow(label: String, subtitle: String, showDisconnect: Boolean, onDisconnect: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = label
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Th.fg(0xFF999999.toInt()))
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(col)
        if (showDisconnect) {
            row.addView(TextView(this).apply {
                text = "Отключить"
                setTextColor(Th.fg(0xFFFF6B5B.toInt()))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener { onDisconnect() }
            })
        }
        return row
    }

    private fun chatPickerDialog(
        title: String,
        chats: List<BackupManager.ChatInfo>,
        onConfirmed: (Set<String>) -> Unit
    ) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())

        val search = plainField("Поиск по названию")
        search.setSingleLine(true)
        col.addView(search)

        val counter = hintText("")
        counter.setPadding(dp(2), dp(8), 0, dp(4))
        col.addView(counter)

        val boxes = ArrayList<CheckBox>()
        val titles = ArrayList<String>()
        fun updateCounter() {
            val n = boxes.count { it.isChecked }
            counter.text = Loc.t("Выбрано: $n из ${boxes.size}")
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        fun visibleBoxes() = boxes.filter { it.visibility == View.VISIBLE }
        fun action(label: String, f: (CheckBox) -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Th.fg(0xFFB39DFF.toInt()))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(6), dp(16), dp(6))
            isClickable = true
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { visibleBoxes().forEach(f); updateCounter() }
        }
        actions.addView(action("Все") { it.isChecked = true })
        actions.addView(action("Сбросить") { it.isChecked = false })
        actions.addView(action("Инвертировать") { it.isChecked = !it.isChecked })
        col.addView(actions)

        val listCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (c in chats) {
            val shownTitle = c.title.ifBlank { Loc.t("Чат") }
            val sub = Loc.t("${c.messages} сообщ.") + (if (c.lastTs > 0) " · " + dateFmt.format(Date(c.lastTs)) else "")
            val sb = android.text.SpannableStringBuilder(shownTitle)
            val start = sb.length
            sb.append("\n").append(sub)
            sb.setSpan(android.text.style.RelativeSizeSpan(0.82f), start, sb.length, 0)
            sb.setSpan(android.text.style.ForegroundColorSpan(Th.fg(0xFF8A8A92.toInt())), start, sb.length, 0)
            val cb = CheckBox(this).apply {
                text = sb
                tag = c.id
                isChecked = true
                setTextColor(Th.fg(0xFFDDDDDD.toInt()))
                textSize = 14f
                setPadding(dp(6), dp(5), 0, dp(5))
                setOnCheckedChangeListener { _, _ -> updateCounter() }
            }
            boxes.add(cb); titles.add(shownTitle.lowercase())
            listCol.addView(cb)
        }
        updateCounter()
        col.addView(ScrollView(this).apply {
            addView(listCol)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250))
        })

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                boxes.forEachIndexed { i, cb ->
                    cb.visibility = if (q.isEmpty() || titles[i].contains(q)) View.VISIBLE else View.GONE
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        lateinit var dialog: android.app.Dialog
        dialog = styledPanelDialog(
            title, col,
            listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Далее", keepOpen = true) {
                    val selected = boxes.filter { it.isChecked }.mapNotNull { it.tag as? String }.toSet()
                    if (selected.isEmpty()) {
                        Toast.makeText(this, Loc.t("Выберите хотя бы один чат"), Toast.LENGTH_SHORT).show()
                    } else {
                        dialog.dismiss()
                        onConfirmed(selected)
                    }
                }
            )
        )
    }

    private fun optionsTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Th.fg(0xFFB39DFF.toInt()))
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun hintText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Th.fg(0xFF8A8A92.toInt()))
        textSize = 12f
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(dp(2), dp(2), 0, dp(2))
    }

    private fun radioGroup(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): android.widget.RadioGroup {
        val group = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        val ids = labels.mapIndexed { i, label ->
            val rb = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                setTextColor(Th.fg(0xFFDDDDDD.toInt()))
                textSize = 14f
                setPadding(dp(6), dp(5), 0, dp(5))
            }
            group.addView(rb)
            rb.id
        }
        group.check(ids[selected.coerceIn(0, ids.lastIndex)])
        group.setOnCheckedChangeListener { _, checkedId -> ids.indexOf(checkedId).takeIf { it >= 0 }?.let(onSelect) }
        return group
    }

    private fun optionCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            isChecked = checked
            setTextColor(Th.fg(0xFFDDDDDD.toInt()))
            textSize = 14f
            setPadding(dp(6), dp(5), 0, dp(5))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    private fun plainField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        setHintTextColor(Th.fg(0xFF666666.toInt()))
        textSize = 14f
        background = getDrawable(R.drawable.bg_input_field)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun promptNewPassword(title: String, onConfirmed: (String) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pw1 = passwordField("Пароль (минимум 4 символа)")
        val pw2 = passwordField("Повторите пароль")
        col.addView(pw1)
        col.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
        })
        col.addView(pw2)
        lateinit var dialog: android.app.Dialog
        dialog = styledPanelDialog(
            title, col,
            listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Создать", keepOpen = true) {
                    val a = pw1.text.toString()
                    val b = pw2.text.toString()
                    when {
                        a.length < 4 -> Toast.makeText(this, Loc.t("Пароль должен быть не короче 4 символов"), Toast.LENGTH_SHORT).show()
                        a != b -> Toast.makeText(this, Loc.t("Пароли не совпадают"), Toast.LENGTH_SHORT).show()
                        else -> {
                            dialog.dismiss()
                            onConfirmed(a)
                        }
                    }
                }
            )
        )
    }

    private fun promptExistingPassword(title: String, onConfirmed: (String) -> Unit) {
        val field = passwordField("Пароль")
        lateinit var dialog: android.app.Dialog
        dialog = styledPanelDialog(
            title, field,
            listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Импортировать", keepOpen = true) {
                    val pw = field.text.toString()
                    if (pw.isEmpty()) {
                        Toast.makeText(this, Loc.t("Введите пароль"), Toast.LENGTH_SHORT).show()
                    } else {
                        dialog.dismiss()
                        onConfirmed(pw)
                    }
                }
            )
        )
    }

    private fun passwordField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        setHintTextColor(Th.fg(0xFF666666.toInt()))
        textSize = 14f
        background = getDrawable(R.drawable.bg_input_field)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        attachEyeToggle()
    }

    private fun progressDialog(message: String): android.app.Dialog {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) }
        })
        row.addView(TextView(this).apply {
            text = message
            setTextColor(Th.fg(0xFFCCCCCC.toInt()))
            textSize = 13.5f
        })
        val dialog = styledPanelDialog("", row, emptyList())
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Th.fg(0xFFB39DFF.toInt()))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(0, dp(10), 0, dp(8))
    }

    private fun card(rows: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_settings_card)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(4)
        layoutParams = lp
        rows.forEachIndexed { i, row ->
            addView(row)
            if (i != rows.lastIndex) addView(View(this@BackupActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(Th.bg(0xFF232329.toInt()))
            })
        }
    }

    private fun actionRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(14), 0, dp(14))
            applyPressAnimation(PressAnimStyle.LIFT)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Th.fg(0xFF999999.toInt()))
            textSize = 12.5f
            setPadding(0, dp(3), 0, 0)
        })
        return row
    }

    private fun infoCard(text: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = getDrawable(R.drawable.bg_settings_card)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(4)
        layoutParams = lp
        addView(TextView(this@BackupActivity).apply {
            this.text = text
            setTextColor(Th.fg(0xFFAAAAAA.toInt()))
            textSize = 13f
            setLineSpacing(dp(3).toFloat(), 1f)
        })
    }
}
