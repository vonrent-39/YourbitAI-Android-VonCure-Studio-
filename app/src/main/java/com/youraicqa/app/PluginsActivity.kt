package com.youraicqa.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.launch

class PluginsActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    private lateinit var container: LinearLayout
    private val importReq = 7301

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugins)
        findViewById<View>(R.id.btnBack).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnImportPlugin).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnImportPlugin).setOnClickListener {
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/octet-stream", "application/json", "text/plain", "*/*"
                ))
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(Intent.createChooser(i, "Установить плагин"), importReq)
        }
        container = findViewById(R.id.pluginsContainer)

        container.addView(pluginCard(
            icon = R.drawable.ic_plug_plugins,
            title = "Расширенный поиск",
            desc = "ИИ сама решает, когда искать, и ищет по разным сайтам: веб, новости, форумы " +
                "(Reddit, Hacker News, StackOverflow), Wikipedia, конкретный сайт — и может открывать страницы.",
            checked = PluginsPrefs.webSearchEnabled(this)
        ) { on -> PluginsPrefs.setWebSearchEnabled(this, on) })

        val installed = PluginStore.load(this)
        if (installed.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "Установленные плагины"
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(10))
            })
            installed.forEach { plugin ->
                val descLine = buildString {
                    append("v${plugin.version}")
                    if (plugin.author.isNotBlank()) append(" · ${plugin.author}")
                    if (plugin.description.isNotBlank()) append(" · ${plugin.description}")
                    if (plugin.permissions.isNotEmpty()) {
                        append("\nДоступ: ")
                        append(plugin.permissions.joinToString(", ") { it.label })
                    }
                    if (PluginPermission.NETWORK in plugin.permissions) {
                        append("\nСеть: ").append(if (plugin.capabilities.networkDomains.isEmpty() || "*" in plugin.capabilities.networkDomains) "все публичные серверы" else plugin.capabilities.networkDomains.sorted().joinToString(", "))
                    }
                    if (PluginPermission.DOWNLOAD in plugin.permissions) {
                        append("\nЗагрузка: ").append(if (plugin.capabilities.downloadDomains.isEmpty() || "*" in plugin.capabilities.downloadDomains) "все публичные серверы" else plugin.capabilities.downloadDomains.sorted().joinToString(", "))
                    }
                }
                container.addView(pluginCard(
                    icon = R.drawable.ic_plug_plugins,
                    title = plugin.name,
                    desc = descLine,
                    checked = plugin.enabled,
                    onEdit = { showEditDialog(plugin) }
                ) { on ->
                    PluginStore.setEnabled(this, plugin.id, on); PluginManager.reload(this)
                    if (PluginPermission.AI_PROVIDER in plugin.permissions) recreate()
                })
                if (plugin.enabled && PluginPermission.AI_PROVIDER in plugin.permissions) {
                    addAiProviderRows(plugin)
                }
                if (PluginPermission.CUSTOM_PANEL in plugin.permissions) {
                    container.addView(TextView(this).apply {
                        text = "→ Открыть панель «${plugin.name}»"
                        setTextColor(Th.fg(0xFF8AB4F8.toInt()))
                        textSize = 13f
                        setPadding(dp(4), 0, 0, dp(10))
                        applyPressAnimation(PressAnimStyle.SOFT)
                        setOnClickListener {
                            startActivity(Intent(this@PluginsActivity, PluginPanelActivity::class.java)
                                .putExtra("plugin_id", plugin.id))
                        }
                    })
                }
                if (plugin.enabled && PluginPermission.UI_ACTION in plugin.permissions) {
                    PluginManager.activeUiActions(this).filter { it.first == plugin.id }.forEach { (pluginId, actionId, title) ->
                        container.addView(TextView(this).apply {
                            text = "→ $title"
                            setTextColor(Th.fg(0xFF8AB4F8.toInt()))
                            textSize = 13f
                            setPadding(dp(4), 0, 0, dp(10))
                            applyPressAnimation(PressAnimStyle.SOFT)
                            setOnClickListener { runPluginAction(plugin, actionId) }
                        })
                    }
                }
            }
        }

        container.addView(TextView(this).apply {
            text = "Установить .plugin можно через кнопку «+» вверху — перед установкой предложим проверить файл на VirusTotal."
            setTextColor(Th.fg(0xFF666666.toInt()))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != importReq || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val displayName = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
            if (!displayName.isNullOrBlank() && !displayName.endsWith(".plugin", true)) {
                throw Exception("Выберите файл с расширением .plugin")
            }
            val fileText = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw Exception("Не удалось прочитать файл")
            val plugin = PluginStore.parse(fileText) ?: throw Exception("Неверный .plugin: нужен JSON с полем id")

            val scan = PluginScanner.scan(fileText, plugin.script)
            if (scan.flagged) {
                NanoLog.log(this, "PLUGIN warning id=${plugin.id} reasons=${scan.reasons.joinToString(";")}")
            }
            showPluginCheckOffer(plugin, scan)
        } catch (e: Exception) {
            showPluginErrorPopup(e.message ?: "Не удалось импортировать .plugin")
        }
    }

    private fun showPluginCheckOffer(plugin: InstalledPlugin, scan: PluginScanner.ScanResult) {
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PluginsActivity).apply {
                text = (if (scan.flagged)
                    "В «${plugin.name}» нашлись подозрительные признаки. Это не доказывает, что плагин вредоносный — но лучше проверить файл."
                else
                    "Плагин «${plugin.name}» перед установкой можно проверить на вирусы.") +
                    "\n\nПо кнопке «Проверить» откроется официальный сайт VirusTotal — загрузите туда выбранный .plugin-файл. Потом вернитесь сюда и нажмите «Установить».\n\n" +
                    "Важно: файл, загруженный на VirusTotal, может стать доступен аналитическим партнёрам и исследователям безопасности. Не отправляй туда плагины с секретами или приватным кодом."
                setTextColor(Th.fg(0xFFE5E5EA.toInt()))
                textSize = 14f
                setPadding(0, dp(2), 0, dp(10))
            })
            if (scan.flagged) {
                addView(TextView(this@PluginsActivity).apply {
                    text = scan.reasons.joinToString("\n\n")
                    setTextColor(Th.fg(0xFF9C9CA5.toInt()))
                    textSize = 12.5f
                })
            }
        }
        styledPanelDialog(
            title = "Проверить плагин?",
            contentView = details,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Проверить", keepOpen = true) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.virustotal.com/gui/home/upload")))
                    } catch (_: Exception) {
                        Toast.makeText(this, Loc.t("Не удалось открыть VirusTotal"), Toast.LENGTH_SHORT).show()
                    }
                },
                StyledDialogButton("Установить", destructive = scan.flagged) {
                    installScannedPlugin(plugin, scan.sha256)
                }
            )
        )
    }

    private fun showPluginErrorPopup(raw: String) {
        val item = AppErrorUi.classify("plugin error: ${raw.take(180)}")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(4), 0, 0)
            addView(ImageView(this@PluginsActivity).apply {
                setImageResource(R.drawable.ic_warning)
                setColorFilter(Th.fg(0xFFFF5C5C.toInt()))
                contentDescription = "Ошибка плагина"
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            })
            addView(TextView(this@PluginsActivity).apply {
                text = item.description + "\n\n" + raw.take(220)
                setTextColor(Th.fg(0xFFE5E5EA.toInt()))
                textSize = 14f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        styledPanelDialog(
            title = "Ошибка E${item.code.toString().padStart(2, '0')} · ${item.title}",
            contentView = content,
            buttons = listOf(StyledDialogButton("Понятно") {})
        )
    }

    private fun installScannedPlugin(plugin: InstalledPlugin, sha256: String) {
        try {
            val checked = plugin.copy(sha256 = sha256)
            PluginAiSettings.clearProviderCache(this, plugin.id)
            PluginStore.install(this, checked)
            PluginManager.reload(this)
            NanoLog.log(this, "PLUGIN installed id=${plugin.id} sha256=$sha256 permissions=${plugin.permissions.joinToString(",")}")
            Toast.makeText(this, Loc.t("Плагин установлен: ${plugin.name}"), Toast.LENGTH_LONG).show()
            recreate()
        } catch (e: Exception) {
            showPluginErrorPopup(e.message ?: "Не удалось установить плагин")
        }
    }

    private fun runPluginAction(plugin: InstalledPlugin, actionId: String) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7302)
        }
        val ctx = org.json.JSONObject()
        plugin.capabilities.networkDomains.sorted().firstOrNull()?.takeIf { it != "*" }?.let { ctx.put("url", "https://$it/") }
        PluginManager.invokeUiAction(this, plugin.id, actionId, ctx)
        Toast.makeText(this, Loc.t("Запущено: ${plugin.name}"), Toast.LENGTH_SHORT).show()
    }

    private fun addAiProviderRows(plugin: InstalledPlugin) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PluginsActivity).apply {
                text = "AI-провайдеры плагина загружаются…"
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 12.5f
                setPadding(dp(4), 0, 0, dp(10))
            })
        }
        container.addView(holder)
        baseScope.launch {
            val providers = PluginManager.awaitAiProviders(this@PluginsActivity, plugin.id)
            if (isFinishing || isDestroyed) return@launch
            holder.removeAllViews()
            if (providers.isEmpty()) {
                holder.addView(TextView(this@PluginsActivity).apply {
                    text = "Плагин пока не зарегистрировал ни одного AI-провайдера."
                    setTextColor(Th.fg(0xFF888888.toInt()))
                    textSize = 12.5f
                    setPadding(dp(4), 0, 0, dp(10))
                })
                return@launch
            }
            providers.forEach { pr ->
                val needsSetup = pr.baseUrl != null || pr.apiKey != null
                holder.addView(TextView(this@PluginsActivity).apply {
                    text = if (needsSetup) "→ Провайдер «${pr.name}»: адрес API и ключ · моделей: ${pr.models.size}"
                    else "Провайдер «${pr.name}» · моделей: ${pr.models.size} · настройки не нужны"
                    setTextColor(Th.fg(if (needsSetup) 0xFF8AB4F8.toInt() else 0xFF888888.toInt()))
                    textSize = 13f
                    setPadding(dp(4), 0, 0, dp(10))
                    if (needsSetup) {
                        applyPressAnimation(PressAnimStyle.SOFT)
                        setOnClickListener { showAiProviderDialog(plugin, pr) }
                    }
                })
            }
        }
    }

    private fun showAiProviderDialog(plugin: InstalledPlugin, provider: PluginAiProvider) {
        val saved = PluginAiSettings.load(this, plugin.id, provider.id)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun field(hintText: String, initial: String, secret: Boolean) = EditText(this).apply {
            hint = hintText
            setText(initial)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            background = getDrawable(R.drawable.bg_input_field)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setSingleLine(true)
            inputType = if (secret)
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            else
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        var baseField: EditText? = null
        var keyField: EditText? = null
        provider.baseUrl?.let { f ->
            baseField = field(
                (if (f.default.isNotBlank()) "Адрес API (по умолчанию ${f.default})" else "Адрес API, например https://api.example.com/v1") +
                    (if (f.required) "" else " — необязательно"),
                saved.baseUrl, secret = false
            ).also { layout.addView(it) }
        }
        provider.apiKey?.let { f ->
            keyField = field(if (f.required) "API-ключ" else "API-ключ (если нужен серверу)", saved.apiKey, secret = true)
                .also { layout.addView(it) }
        }
        layout.addView(TextView(this).apply {
            text = "Адрес и ключ получит только код плагина «${plugin.name}» — при генерации ответа моделью этого провайдера. " +
                "Укажите ключ, только если доверяете плагину. Локальные и внутренние адреса (localhost, 192.168.*) плагинам недоступны."
            setTextColor(Th.fg(0xFF8C8C96.toInt()))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })

        var dialog: android.app.Dialog? = null
        dialog = styledPanelDialog(
            title = "Провайдер: ${provider.name}",
            contentView = layout,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Сохранить", keepOpen = true) {
                    val rawUrl = baseField?.text?.toString().orEmpty()
                    val url = PluginAiLogic.normalizeBaseUrl(rawUrl)
                    if (url == null) {
                        Toast.makeText(this, Loc.t("Некорректный адрес: нужен http(s)://…"), Toast.LENGTH_SHORT).show()
                        return@StyledDialogButton
                    }
                    val key = keyField?.text?.toString().orEmpty().trim()
                    PluginAiSettings.save(this, plugin.id, provider.id, PluginAiSettingsValue(url, key))
                    Toast.makeText(this, Loc.t("Сохранено"), Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                }
            )
        )
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun pluginCard(
        icon: Int, title: String, desc: String, checked: Boolean,
        onEdit: (() -> Unit)? = null, onToggle: (Boolean) -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_settings_card)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (onEdit != null) setOnClickListener { onEdit() }
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = desc
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 13f
            setPadding(0, dp(4), dp(10), 0)
        })
        if (onEdit != null) {
            col.addView(TextView(this).apply {
                text = "Изменить название/описание/автора"
                setTextColor(Th.fg(0xFF5FA8FF.toInt()))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener { onEdit() }
            })
        }
        card.addView(col)
        card.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onToggle(on) }
        })
        return card
    }

    private fun showEditDialog(plugin: InstalledPlugin) {
        val pad = dp(20)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun styledField(hintText: String, initial: String) = EditText(this).apply {
            hint = hintText
            setText(initial)
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            setHintTextColor(Th.fg(0xFF666666.toInt()))
            background = getDrawable(R.drawable.bg_input_field)
            setPadding(dp(12).toInt(), dp(10).toInt(), dp(12).toInt(), dp(10).toInt())
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10).toInt()
            layoutParams = lp
        }
        val nameField = styledField("Название", plugin.name)
        val descField = styledField("Описание", plugin.description)
        val authorField = styledField("Автор", plugin.author)
        layout.addView(nameField)
        layout.addView(descField)
        layout.addView(authorField)

        styledPanelDialog(
            title = "Плагин: ${plugin.id}",
            contentView = layout,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Удалить", keepOpen = false, destructive = true) {
                    styledConfirmDialog(
                        title = "Удалить плагин?",
                        message = "«${plugin.name}» будет удалён без возможности восстановить.",
                        positiveText = "Удалить",
                        destructive = true
                    ) {
                        PluginStore.remove(this, plugin.id)
                        PluginAiSettings.clearPlugin(this, plugin.id)
                        PluginManager.reload(this)
                        recreate()
                    }
                },
                StyledDialogButton("Сохранить") {
                    PluginStore.update(
                        this, plugin.id,
                        nameField.text.toString(), descField.text.toString(), authorField.text.toString()
                    )
                    Toast.makeText(this, Loc.t("Сохранено"), Toast.LENGTH_SHORT).show()
                    recreate()
                }
            )
        )
    }
}
