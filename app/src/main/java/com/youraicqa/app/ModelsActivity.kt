package com.youraicqa.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.github.junrar.Archive
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModelsActivity : NanoBaseActivity() {
    override val staggerEntrance: Boolean get() = true
    private lateinit var container: LinearLayout
    private val importReq = 5501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        AchievementsStore.onTabOpened(this, "models")
        findViewById<View>(R.id.btnBack).applyPressAnimation(PressAnimStyle.SOFT)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        container = findViewById(R.id.modelsContainer)
        refresh()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun refresh() {
        container.removeAllViews()

        container.addView(TextView(this).apply {
            text = "Свои модели"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        container.addView(TextView(this).apply {
            text = "Подключите модель по своему провайдеру: укажите ID и URL API. API-ключ можно сохранить один раз у провайдера и использовать для нескольких моделей."
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 13f
            setPadding(0, 0, 0, dp(14))
        })

        container.addView(addActionRow(R.drawable.ic_wifi_models, "Добавить провайдера", "Сохраните URL и один API-ключ для нескольких своих моделей") {
            showProviderDialog(null)
        })
        container.addView(addActionRow(R.drawable.ic_wifi_models, "Добавить модель к провайдеру", "Свой или встроенный провайдер (OpenRouter, Groq, NVIDIA…) — ключ подставится сам, останется указать ID модели") {
            showPickProviderDialog()
        })
        container.addView(addActionRow(R.drawable.ic_wifi_models, "Добавить сервер", "Адрес своего сервера: модели подтянутся с него сами") {
            showServerDialog(null)
        })
        container.addView(addActionRow(R.drawable.ic_wifi_models, "Добавить по ссылке", "ID модели и провайдер; ключ модели можно оставить пустым") {
            showAddByLinkDialog()
        })
        container.addView(addActionRow(R.drawable.ic_package_box, "Импорт из архива", "ZIP/RAR, model.json/manifest.json, или GGUF-файл с Hugging Face — запустится прямо на устройстве") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-rar-compressed", "*/*"))
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(i, importReq)
        })

        val providers = ProvidersStore.load(this)
        container.addView(TextView(this).apply {
            text = "Мои провайдеры"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(10))
        })
        if (providers.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Провайдеров пока нет. Можно хранить ключ прямо у модели или добавить общий провайдер."
                setTextColor(Th.fg(0xFF777777.toInt()))
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            })
        } else {
            providers.forEach { container.addView(providerCard(it)) }
        }

        val servers = ServerStore.load(this)
        container.addView(TextView(this).apply {
            text = "Мои серверы"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(10))
        })
        if (servers.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Серверов пока нет. Подключите свой сервер — его модели появятся в списке моделей."
                setTextColor(Th.fg(0xFF777777.toInt()))
                textSize = 13f
                setPadding(0, 0, 0, dp(8))
            })
        } else {
            servers.forEach { container.addView(serverCard(it)) }
        }

        val list = ModelsStore.load(this)
        container.addView(TextView(this).apply {
            text = "Добавленные модели"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(10))
        })
        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Пока нет своих моделей."
                setTextColor(Th.fg(0xFF777777.toInt()))
                textSize = 13f
            })
        } else {
            for (m in list) container.addView(modelCard(m))
        }
    }

    private fun addActionRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_chat_row_card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Th.fg(0xFF8AB4F8.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Th.bg(0xFF1E1E26.toInt()))
                cornerRadius = dp(12).toFloat()
            }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = subtitle
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "›"
            setTextColor(Th.fg(0xFF666666.toInt()))
            textSize = 20f
        })
        return row
    }

    private fun providerCard(p: CustomProvider): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_chat_row_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = p.name
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = p.baseUrl
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12f
            maxLines = 1
        })
        col.addView(TextView(this).apply {
            text = if (p.apiKey.isBlank()) "ключ не задан" else "ключ сохранён"
            setTextColor(if (p.apiKey.isBlank()) Th.fg(0xFF777777.toInt()) else Th.fg(0xFF4CAF50.toInt()))
            textSize = 11.5f
            setPadding(0, dp(3), 0, 0)
        })
        card.addView(col)
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_pencil_asset)
            setColorFilter(Th.fg(0xFF8AB4F8.toInt()))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Th.bg(0xFF1E1E26.toInt())); cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) }
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { showProviderDialog(p) }
        })
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(Th.fg(0xFFE57373.toInt()))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Th.bg(0xFF241A1A.toInt())); cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                styledConfirmDialog("Удалить провайдера?", "Модели останутся, но перестанут получать ключ этого провайдера.", "Удалить", destructive = true) {
                    ProvidersStore.remove(this@ModelsActivity, p)
                    refresh()
                }
            }
        })
        return card
    }

    private fun showProviderDialog(existing: CustomProvider?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(4)) }
        val name = dialogField("Название провайдера")
        val url = dialogField("URL OpenAI-совместимого API (https://...)")
        val key = dialogField("API-ключ провайдера", password = true)
        if (existing != null) { name.setText(existing.name); url.setText(existing.baseUrl); key.setText(existing.apiKey) }
        form.addView(name); form.addView(url); form.addView(key)
        styledPanelDialog(
            title = if (existing == null) "Добавить провайдера" else "Редактировать провайдера",
            contentView = form,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton(if (existing == null) "Добавить" else "Сохранить") {
                    val n = name.text.toString().trim()
                    val u = url.text.toString().trim().trimEnd('/')
                    if (u.isBlank()) {
                        Toast.makeText(this, Loc.t("Укажите URL провайдера"), Toast.LENGTH_SHORT).show()
                    } else {
                        ProvidersStore.add(this, CustomProvider(existing?.id ?: java.util.UUID.randomUUID().toString(), n.ifBlank { u }, u, key.text.toString().trim()))
                        Toast.makeText(this, Loc.t("Провайдер сохранён"), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            )
        )
    }

    private fun showPickProviderDialog() {
        val providers = ProvidersStore.load(this)
        var dlg: android.app.Dialog? = null
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        fun sectionTitle(t: String, hint: String? = null) {
            list.addView(TextView(this).apply {
                text = t
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(20), dp(6), dp(20), dp(if (hint == null) 8 else 2))
            })
            if (hint != null) list.addView(TextView(this).apply {
                text = hint
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 12f
                setPadding(dp(20), 0, dp(20), dp(8))
            })
        }
        fun providerRow(title: String, subtitle: String, keyReady: Boolean, keyNote: String, onPick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.bg_chat_row_card)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(8); marginStart = dp(20); marginEnd = dp(20) }
                applyPressAnimation(PressAnimStyle.SOFT)
                setOnClickListener {
                    dlg?.dismiss()
                    onPick()
                }
            }
            row.addView(TextView(this).apply {
                text = title
                setTextColor(Th.fg(0xFFFFFFFF.toInt()))
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
            })
            if (subtitle.isNotBlank()) row.addView(TextView(this).apply {
                text = subtitle
                setTextColor(Th.fg(0xFF888888.toInt()))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(TextView(this).apply {
                text = keyNote
                setTextColor(if (keyReady) Th.fg(0xFF4CAF50.toInt()) else Th.fg(0xFF777777.toInt()))
                textSize = 11.5f
                setPadding(0, dp(3), 0, 0)
            })
            list.addView(row)
        }

        sectionTitle("Встроенные провайдеры", "Добавьте свою модель к провайдеру приложения — ключ берётся из «API-ключи».")
        val sp = getSharedPreferences("nanoai", android.content.Context.MODE_PRIVATE)
        ApiKeyGate.BUILTIN_PROVIDERS.forEach { bp ->
            val hasKey = sp.getString(bp, "")?.trim().orEmpty().isNotBlank()
            val note = when {
                hasKey -> "ключ сохранён"
                ApiKeyGate.keyOptional(bp) -> "ключ необязателен"
                else -> "ключ не задан — добавьте в «API-ключи»"
            }
            providerRow(ApiKeyGate.providerLabel(bp), "", hasKey || ApiKeyGate.keyOptional(bp), note) { showBuiltinModelDialog(bp, null) }
        }
        sectionTitle("Мои провайдеры")
        if (providers.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Своих провайдеров пока нет — добавьте через «Добавить провайдера»."
                setTextColor(Th.fg(0xFF777777.toInt()))
                textSize = 12.5f
                setPadding(dp(20), 0, dp(20), dp(8))
            })
        }
        providers.forEach { p ->
            providerRow(p.name, p.baseUrl, p.apiKey.isNotBlank(), if (p.apiKey.isBlank()) "ключ не задан" else "ключ сохранён") {
                showProviderModelDialog(p)
            }
        }
        val scroll = android.widget.ScrollView(this).apply { addView(list) }
        dlg = styledPanelDialog(
            title = "Выбор провайдера",
            contentView = scroll,
            buttons = listOf(StyledDialogButton("Отмена") {})
        )
    }

    private fun showBuiltinModelDialog(provider: String, existing: CustomModel?) {
        val label = ApiKeyGate.providerLabel(provider)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        form.addView(TextView(this).apply {
            text = Loc.t("Провайдер:") + " $label"
            setTextColor(Th.fg(0xFF9AA79A.toInt()))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        })
        val idField = dialogField(when (provider) {
            "openrouter" -> "ID модели (например meta-llama/llama-3.3-70b-instruct:free)"
            "nvidia" -> "ID модели (например nvidia/llama-3.1-nemotron-70b-instruct)"
            "gemini" -> "ID модели (например gemini-2.5-flash)"
            "groq" -> "ID модели (например llama-3.3-70b-versatile)"
            "cloudflare" -> "ID модели (например @cf/meta/llama-3.1-8b-instruct)"
            else -> "ID модели у провайдера"
        })
        val nameField = dialogField("Название (необязательно)")
        val tokenLimitField = dialogField("Лимит токенов на ответ (необязательно)")
        tokenLimitField.inputType = InputType.TYPE_CLASS_NUMBER
        if (existing != null) {
            idField.setText(existing.id)
            nameField.setText(existing.name)
            if (existing.tokenLimit > 0) tokenLimitField.setText(existing.tokenLimit.toString())
        }
        form.addView(idField)
        form.addView(nameField)
        form.addView(tokenLimitField)
        val vision = android.widget.CheckBox(this).apply {
            text = "Модель понимает изображения (vision)"
            isChecked = existing != null && ModelModes.VISION in ModelModes.parse(existing.modes)
            setTextColor(Th.fg(0xFFE8E8E8.toInt()))
            textSize = 14f
        }
        form.addView(vision)
        val hasKey = getSharedPreferences("nanoai", android.content.Context.MODE_PRIVATE).getString(provider, "")?.trim().orEmpty().isNotBlank()
        if (!hasKey && !ApiKeyGate.keyOptional(provider)) {
            form.addView(TextView(this).apply {
                text = "Для этого провайдера не задан API-ключ — модель появится в списке, но запросы заработают после добавления ключа в «API-ключи»."
                setTextColor(Th.fg(0xFFE0A040.toInt()))
                textSize = 11.5f
                setPadding(0, dp(8), 0, dp(6))
            })
        }
        var formDlg: android.app.Dialog? = null
        formDlg = styledPanelDialog(
            title = if (existing == null) "Модель для провайдера" else "Редактировать модель",
            contentView = form,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton(if (existing == null) "Добавить" else "Сохранить", keepOpen = true) {
                    val id = idField.text.toString().trim()
                    if (id.isBlank()) {
                        Toast.makeText(this, Loc.t("Укажите ID модели"), Toast.LENGTH_SHORT).show()
                    } else {
                        val name = nameField.text.toString().trim().ifBlank { id }
                        val tokenLimit = tokenLimitField.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                        val modes = ModelModes.join(if (vision.isChecked) listOf(ModelModes.CHAT, ModelModes.VISION) else listOf(ModelModes.CHAT))
                        val cm = CustomModel(id, name, ModelsStore.builtinMarker(provider), "", "link", tokenLimit, modes = modes, splitModes = false)
                        if (existing == null) ModelsStore.add(this, cm) else ModelsStore.update(this, existing, cm)
                        Toast.makeText(this, Loc.t("Модель добавлена: $name"), Toast.LENGTH_SHORT).show()
                        refresh()
                        formDlg?.dismiss()
                    }
                }
            )
        )
    }

    private fun showProviderModelDialog(provider: CustomProvider) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        form.addView(TextView(this).apply {
            text = Loc.t("Провайдер:") + " ${provider.name}\n${provider.baseUrl}"
            setTextColor(Th.fg(0xFF9AA79A.toInt()))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        })
        val idField = dialogField("ID модели (например gpt-4o-mini)")
        val nameField = dialogField("Название (необязательно)")
        val tokenLimitField = dialogField("Лимит токенов на ответ (необязательно)")
        tokenLimitField.inputType = InputType.TYPE_CLASS_NUMBER
        form.addView(idField)
        form.addView(nameField)
        form.addView(tokenLimitField)
        val readModesP = addModesSection(form, null)
        if (provider.apiKey.isBlank()) {
            form.addView(TextView(this).apply {
                text = "У этого провайдера не задан API-ключ — запросы пойдут без ключа. Ключ можно добавить, отредактировав провайдера."
                setTextColor(Th.fg(0xFFE0A040.toInt()))
                textSize = 11.5f
                setPadding(0, dp(2), 0, dp(6))
            })
        }
        var formDlg: android.app.Dialog? = null
        formDlg = styledPanelDialog(
            title = "Модель для провайдера",
            contentView = form,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Добавить", keepOpen = true) {
                    val id = idField.text.toString().trim()
                    if (id.isBlank()) {
                        Toast.makeText(this, Loc.t("Укажите ID модели"), Toast.LENGTH_SHORT).show()
                    } else {
                        val name = nameField.text.toString().trim().ifBlank { id }
                        val tokenLimit = tokenLimitField.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                        val (mm, sp, ia) = readModesP()
                        val io = imageOptsReader?.invoke()
                        ModelsStore.add(this, CustomModel(id, name, provider.baseUrl, "", "link", tokenLimit, modes = mm, splitModes = sp, imageApi = ia,
                            imageSize = io?.size.orEmpty(), imageSteps = io?.steps ?: 0, imageNegative = io?.negative.orEmpty()))
                        Toast.makeText(this, Loc.t("Модель добавлена: $name"), Toast.LENGTH_SHORT).show()
                        refresh()
                        formDlg?.dismiss()
                    }
                }
            )
        )
    }

    private val ui = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onDestroy() {
        super.onDestroy()
        ui.cancel()
    }

    private fun online(): Boolean? = CustomServerClient.isOnline(this)

    private fun errText(e: Throwable): String =
        (if (e is ServerException) e.error else ServerErrors.fromThrowable(e, online())).display()

    private fun serverCard(s: CustomServer): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_chat_row_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = s.name
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = ServerLogic.hostOf(s.baseUrl) ?: s.baseUrl
            setTextColor(Th.fg(0xFF888888.toInt()))
            textSize = 12f
            maxLines = 1
        })
        col.addView(TextView(this).apply {
            text = if (!s.enabled) "отключён" else if (s.models.isEmpty()) "пока нет моделей" else "моделей: ${s.models.size}"
            setTextColor(if (!s.enabled) Th.fg(0xFFFFB74D.toInt()) else if (s.models.isEmpty()) Th.fg(0xFF777777.toInt()) else Th.fg(0xFF4CAF50.toInt()))
            textSize = 11.5f
            setPadding(0, dp(3), 0, 0)
        })
        if (s.keyLost) {
            col.addView(TextView(this).apply {
                text = "ключ не задан, введите заново"
                setTextColor(Th.fg(0xFFFFB74D.toInt()))
                textSize = 11.5f
                setPadding(0, dp(2), 0, 0)
            })
        }
        col.addView(serverSwitch("Включён", s.enabled).apply {
            setOnCheckedChangeListener { _, checked ->
                ServerStore.setEnabled(this@ModelsActivity, s.id, checked)
                Toast.makeText(
                    this@ModelsActivity,
                    if (checked) "Сервер включён" else "Сервер отключён: его модели скрыты",
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
        })
        card.addView(col)
        card.alpha = if (s.enabled) 1f else 0.6f
        fun iconBtn(res: Int, tint: Int, bg: Int, last: Boolean, onClick: () -> Unit) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(Th.fg(tint))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Th.bg(bg)); cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { if (!last) marginEnd = dp(8) }
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener { onClick() }
        }
        card.addView(iconBtn(R.drawable.ic_regenerate, 0xFF81C784.toInt(), 0xFF1A241C.toInt(), false) { refreshServerModels(s) })
        card.addView(iconBtn(R.drawable.ic_pencil_asset, 0xFF8AB4F8.toInt(), 0xFF1E1E26.toInt(), false) { showServerDialog(s) })
        card.addView(iconBtn(R.drawable.ic_trash, 0xFFE57373.toInt(), 0xFF241A1A.toInt(), true) {
            styledConfirmDialog("Удалить сервер?", "Модели этого сервера пропадут из списка. Остальные модели, провайдеры и ключи не затронуты.", "Удалить", destructive = true) {
                ServerStore.remove(this@ModelsActivity, s.id)
                CustomServerClient.addressMemory.forget(s.id)
                refresh()
            }
        })
        return card
    }

    private fun refreshServerModels(s: CustomServer) {
        if (!s.enabled) {
            Toast.makeText(this, Loc.t("Сервер отключён — сначала включите его"), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, Loc.t("Обновляю список моделей…"), Toast.LENGTH_SHORT).show()
        ui.launch {
            try {
                val info = CustomServerClient.listModelsInfo(s, isOnline = { online() })
                ServerStore.updateModelsInfo(this@ModelsActivity, s.id, Result.success(info))
                val list = info.ids
                Toast.makeText(
                    this@ModelsActivity,
                    if (list.isEmpty()) "Сервер отвечает, но моделей не отдал — список остался прежним"
                    else "Сервер отвечает, моделей: ${list.size}" +
                        (if (info.managed.isNotEmpty()) ", на ключах сервера: ${info.managed.size}" else ""),
                    Toast.LENGTH_LONG
                ).show()
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@ModelsActivity, errText(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun serverSwitch(label: String, checked: Boolean): android.widget.Switch = android.widget.Switch(this).apply {
        text = label
        isChecked = checked
        setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        textSize = 14f
        setPadding(0, dp(6), 0, dp(6))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun serverResultLine(): TextView = TextView(this).apply {
        textSize = 12.5f
        setTextColor(Th.fg(0xFF888888.toInt()))
        setPadding(0, dp(4), 0, 0)
        visibility = View.GONE
    }

    private fun TextView.show(msg: String, ok: Boolean?) {
        text = msg
        setTextColor(Th.fg(when (ok) { true -> 0xFF4CAF50.toInt(); false -> 0xFFE57373.toInt(); null -> 0xFF888888.toInt() }))
        visibility = View.VISIBLE
    }

    private suspend fun askTrustFingerprint(host: String, info: CustomServerClient.CertInfo, previousPin: String): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)
            fun answer(v: Boolean) { if (answered.compareAndSet(false, true) && cont.isActive) cont.resume(v) {} }
            val changedNote = if (previousPin.isNotBlank())
                "ВНИМАНИЕ: раньше был закреплён другой отпечаток — сертификат сервера изменился.\n\n" else ""
            val until = if (info.notAfterMs > 0) java.text.DateFormat.getDateInstance().format(java.util.Date(info.notAfterMs)) else "—"
            val body = TextView(this).apply {
                text = changedNote + "Сервер: $host\nСертификат: ${info.subject.ifBlank { "—" }}\nДействует до: $until\n\n" +
                    "SHA-256:\n${ServerLogic.formatFingerprint(info.fingerprintHex)}\n\n" +
                    "Сверьте отпечаток с тем, что показывает ваш сервер. Если не уверены — нажмите «Отмена»."
                setTextColor(Th.fg(0xFFCCCCCC.toInt()))
                textSize = 13f
                setTextIsSelectable(true)
            }
            val dlg = styledPanelDialog("Сертификат сервера", body, listOf(
                StyledDialogButton("Отмена") { answer(false) },
                StyledDialogButton("Доверять") { answer(true) }
            ))
            dlg.setOnDismissListener { answer(false) }
            cont.invokeOnCancellation { try { dlg.dismiss() } catch (_: Exception) {} }
        }

    private fun showServerDialog(existing: CustomServer?) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(8), dp(4), dp(4)) }
        val name = dialogField("Название сервера")
        val url = dialogField("Адрес сервера (https://...)")
        val fallback = dialogField("Запасной адрес (необязательно)")
        val key = dialogField(if (existing?.keyLost == true) "Ключ не задан — введите заново" else "Ключ (необязательно)", password = true)
        url.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        fallback.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        if (existing != null) {
            name.setText(existing.name); url.setText(existing.baseUrl); fallback.setText(existing.fallbackUrl); key.setText(existing.apiKey)
        }
        val streamSw = serverSwitch("Потоковый ответ", existing?.streaming ?: true)
        val trustSw = serverSwitch("Доверять самоподписанному сертификату", existing?.trustSelfSigned ?: false)
        val trustHint = TextView(this).apply {
            text = "Принимается только сертификат с подтверждённым вами отпечатком SHA-256. Подтвердить его можно кнопкой «Проверить»."
            setTextColor(Th.fg(0xFF777777.toInt())); textSize = 11.5f
            visibility = if (trustSw.isChecked) View.VISIBLE else View.GONE
        }
        trustSw.setOnCheckedChangeListener { _, on -> trustHint.visibility = if (on) View.VISIBLE else View.GONE }
        val checkBtn = TextView(this).apply {
            text = "Проверить"
            setTextColor(Th.fg(0xFFFFFFFF.toInt())); textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_settings_card)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            applyPressAnimation(PressAnimStyle.SOFT)
        }
        val resMain = serverResultLine()
        val resFallback = serverResultLine()
        listOf(name, url, fallback, key, streamSw, trustSw, trustHint, checkBtn, resMain, resFallback).forEach { form.addView(it) }

        var formPin = existing?.pinnedCertSha256.orEmpty()
        var pinHost: String? = existing?.let { ServerLogic.hostOf(it.baseUrl) }
        var checkedModels: List<String>? = null
        var checkedManaged: List<String>? = null
        var checkJob: kotlinx.coroutines.Job? = null

        fun draft(): Pair<CustomServer?, String?> {
            val base = when (val r = ServerLogic.normalizeBaseUrl(url.text.toString())) {
                is ServerLogic.UrlResult.Ok -> r.url
                is ServerLogic.UrlResult.Error -> return null to r.message
            }
            val fbRaw = fallback.text.toString()
            val fb = if (fbRaw.isBlank()) "" else when (val r = ServerLogic.normalizeBaseUrl(fbRaw)) {
                is ServerLogic.UrlResult.Ok -> r.url
                is ServerLogic.UrlResult.Error -> return null to "Запасной адрес: " + r.message
            }
            val trust = trustSw.isChecked
            val pin = if (trust && pinHost != null && pinHost == ServerLogic.hostOf(base)) formPin else ""
            val keyText = key.text.toString().trim()
            return CustomServer(
                id = existing?.id ?: "draft",
                name = name.text.toString().trim().ifBlank { ServerLogic.hostOf(base) ?: base },
                baseUrl = base, fallbackUrl = fb, apiKey = keyText,
                streaming = streamSw.isChecked, trustSelfSigned = trust, pinnedCertSha256 = pin,
                hostConfirmed = existing != null && existing.hostConfirmed && ServerLogic.sameHost(existing.baseUrl, base),
                models = existing?.models ?: emptyList(),
                managedModels = existing?.managedModels ?: emptyList(),
                keyLost = existing?.keyLost == true && keyText.isEmpty(),
                enabled = existing?.enabled ?: true
            ) to null
        }

        checkBtn.setOnClickListener {
            val (d0, err) = draft()
            if (d0 == null) { resMain.show(err ?: "Проверьте адрес", false); resFallback.visibility = View.GONE; return@setOnClickListener }
            checkJob?.cancel()
            resMain.show("Проверяю…", null); resFallback.visibility = View.GONE
            checkBtn.isEnabled = false
            checkJob = ui.launch {
                try {
                    var d: CustomServer = d0
                    var pinNote = ""
                    if (d.trustSelfSigned) {
                        val info = try {
                            CustomServerClient.fetchCertificate(d.baseUrl, isOnline = { online() })
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Exception) { resMain.show(errText(e), false); return@launch }
                        if (info.fingerprintHex != d.pinnedCertSha256) {
                            val host = ServerLogic.hostOf(d.baseUrl) ?: d.baseUrl
                            if (!askTrustFingerprint(host, info, d.pinnedCertSha256)) {
                                resMain.show("Отпечаток сертификата не подтверждён.", false); return@launch
                            }
                            formPin = info.fingerprintHex; pinHost = ServerLogic.hostOf(d.baseUrl)
                            d = d.copy(pinnedCertSha256 = formPin)
                            pinNote = " Отпечаток принят — нажмите «Сохранить», чтобы он запомнился."
                        }
                    }
                    try {
                        val info = CustomServerClient.listModelsAt(d.baseUrl, d, isOnline = { online() })
                        val list = info.ids
                        resMain.show(
                            "Сервер отвечает, моделей: ${list.size}." +
                                (if (info.managed.isNotEmpty()) " На ключах сервера: ${info.managed.size} — они заменят встроенные модели с теми же названиями." else "") +
                                pinNote,
                            true
                        )
                        val merged = ServerLogic.applyRefresh(d.models, Result.success(list))
                        checkedModels = merged
                        checkedManaged = if (list.isEmpty()) d.managedModels else info.managed
                        if (existing != null) { ServerStore.updateModelsInfo(this@ModelsActivity, existing.id, Result.success(info)); refreshBehind() }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e
                    } catch (e: Exception) { resMain.show(errText(e), false) }
                    if (d.fallbackUrl.isNotBlank()) {
                        try {
                            val list = CustomServerClient.listModelsAt(d.fallbackUrl, d, isOnline = { online() })
                            resFallback.show("Запасной адрес отвечает, моделей: ${list.ids.size}.", true)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Exception) { resFallback.show("Запасной адрес: " + errText(e), false) }
                    }
                } finally { checkBtn.isEnabled = true }
            }
        }

        lateinit var dlg: android.app.Dialog
        dlg = styledPanelDialog(
            title = if (existing == null) "Добавить сервер" else "Редактировать сервер",
            contentView = ScrollView(this).apply { addView(form) },
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton(if (existing == null) "Добавить" else "Сохранить", keepOpen = true) {
                    val (d, err) = draft()
                    if (d == null) { Toast.makeText(this, err ?: "Проверьте адрес", Toast.LENGTH_LONG).show(); return@StyledDialogButton }
                    if (d.trustSelfSigned && d.pinnedCertSha256.isBlank()) {
                        Toast.makeText(this, Loc.t("Нажмите «Проверить», чтобы подтвердить отпечаток сертификата"), Toast.LENGTH_LONG).show()
                        return@StyledDialogButton
                    }
                    val toSave = d.copy(id = existing?.id ?: ServerStore.newId(), models = existing?.models ?: checkedModels ?: emptyList(),
                        managedModels = existing?.managedModels ?: checkedManaged ?: emptyList())
                    try {
                        if (existing == null) ServerStore.add(this, toSave) else ServerStore.update(this, toSave)
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "Не удалось сохранить сервер", Toast.LENGTH_LONG).show()
                        return@StyledDialogButton
                    }
                    checkJob?.cancel()
                    dlg.dismiss()
                    Toast.makeText(this, Loc.t("Сервер сохранён"), Toast.LENGTH_SHORT).show()
                    refresh()
                    ui.launch {
                        val fresh = ServerStore.find(this@ModelsActivity, toSave.id) ?: return@launch
                        val res = try {
                            Result.success(CustomServerClient.listModelsInfo(fresh, isOnline = { online() }))
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (e: Exception) { Result.failure<ServerLogic.ParsedModels>(e) }
                        ServerStore.updateModelsInfo(this@ModelsActivity, toSave.id, res)
                        refresh()
                    }
                }
            )
        )
        dlg.setOnDismissListener { checkJob?.cancel() }
    }

    private fun refreshBehind() = refresh()

    private fun modelCard(m: CustomModel): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.bg_chat_row_card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        card.addView(TextView(this).apply {
            text = m.name.trim().take(1).uppercase().ifBlank { "M" }
            setTextColor(Th.fg(0xFF8AB4F8.toInt()))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Th.bg(0xFF1E1E26.toInt()))
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) }
        })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = m.name
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 15.5f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        })
        col.addView(TextView(this).apply {
            text = "ID: ${m.id}"
            setTextColor(Th.fg(0xFF999999.toInt()))
            textSize = 12.5f
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
        })
        col.addView(TextView(this).apply {
            val bp = ModelsStore.builtinProviderOf(m.providerUrl)
            text = if (m.source == "local") "на устройстве · ctx ${m.contextSize} · ${m.threads} поток(ов)"
                else if (bp != null) "Встроенный провайдер: " + ApiKeyGate.providerLabel(bp)
                else m.providerUrl
            setTextColor(Th.fg(0xFF777777.toInt()))
            textSize = 12f
            maxLines = 1
        })
        val badgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
        }
        badgesRow.addView(TextView(this).apply {
            val (label, color) = when {
                ModelsStore.builtinProviderOf(m.providerUrl) != null -> "встроенный провайдер" to 0xFF7CFFB2.toInt()
                else -> when (m.source) {
                "archive" -> "из архива" to 0xFFB39DFF.toInt()
                "file" -> "файл (не GGUF)" to 0xFFFFB74D.toInt()
                "local" -> "на устройстве (GGUF)" to 0xFFFFD54F.toInt()
                else -> "по ссылке" to 0xFF8AB4F8.toInt()
                }
            }
            text = label
            setTextColor(Th.fg(0xFF101114.toInt()))
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Th.bg(color))
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
        })
        for (md in ModelModes.parse(m.modes)) {
            if (md == ModelModes.CHAT || (m.source == "local" && md != ModelModes.IMAGE)) continue
            badgesRow.addView(TextView(this).apply {
                text = ModelModes.label(md)
                setTextColor(Th.fg(0xFF101114.toInt()))
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Th.bg(if (md == ModelModes.VISION) 0xFF64B5F6.toInt() else 0xFFCE93D8.toInt()))
                    cornerRadius = dp(20).toFloat()
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
            })
        }
        if (m.tokenLimit > 0) {
            badgesRow.addView(TextView(this).apply {
                text = "лимит: ${m.tokenLimit} ток."
                setTextColor(Th.fg(0xFFCCCCCC.toInt()))
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Th.bg(0xFF2A2A32.toInt()))
                    cornerRadius = dp(20).toFloat()
                }
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
            })
        }
        col.addView(badgesRow)
        card.addView(col)

        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_pencil_asset)
            setColorFilter(Th.fg(0xFF8AB4F8.toInt()))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Th.bg(0xFF1E1E26.toInt()))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                val bp = ModelsStore.builtinProviderOf(m.providerUrl)
                if (bp != null) showBuiltinModelDialog(bp, m) else showEditModelDialog(m)
            }
        })

        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_trash)
            setColorFilter(Th.fg(0xFFE57373.toInt()))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Th.bg(0xFF241A1A.toInt()))
                cornerRadius = dp(16).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            applyPressAnimation(PressAnimStyle.SOFT)
            setOnClickListener {
                styledConfirmDialog(
                    title = "Удалить модель?",
                    message = "«${m.name}» будет удалена из списка без возможности восстановить.",
                    positiveText = "Удалить",
                    destructive = true
                ) {
                    ModelsStore.remove(this@ModelsActivity, m)
                    Toast.makeText(this@ModelsActivity, Loc.t("Удалено: ${m.name}"), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        })
        return card
    }

    private data class ImageOpts(val url: String, val model: String, val size: String, val steps: Int, val negative: String)
    private var imageOptsReader: (() -> ImageOpts)? = null

    private fun localModesChecked(modes: String, io: ImageOpts?): String {
        val set = ModelModes.parse(modes)
        if (ModelModes.IMAGE in set && (io == null || io.url.isBlank())) {
            Toast.makeText(this, Loc.t("Адрес сервера изображений не указан — генерация изображений не включена (GGUF на устройстве сам картинки не рисует)."), Toast.LENGTH_LONG).show()
            return ModelModes.join(set - ModelModes.IMAGE)
        }
        return ModelModes.join(set)
    }

    private fun addModesSection(form: LinearLayout, current: CustomModel?, local: Boolean = false): () -> Triple<String, Boolean, String> {
        val cur = ModelModes.parse(current?.modes ?: ModelModes.CHAT)
        form.addView(TextView(this).apply {
            text = "Режимы модели"
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
        form.addView(TextView(this).apply {
            text = "Отметьте, что умеет модель: облачная (по ссылке) или на локальном сервере (Ollama, LM Studio, Automatic1111). Любой режим можно выключить позже."
            setTextColor(Th.fg(0xFF777777.toInt()))
            textSize = 11.5f
            setPadding(0, 0, 0, dp(4))
        })
        fun box(label: String, on: Boolean) = android.widget.CheckBox(this).apply {
            text = label
            isChecked = on
            setTextColor(Th.fg(0xFFE8E8E8.toInt()))
            textSize = 14f
        }
        val chat = box("Чат (текст)", ModelModes.CHAT in cur)
        val vision = box("Распознавание изображений (vision)", ModelModes.VISION in cur)
        val image = box("Генерация изображений", ModelModes.IMAGE in cur)
        form.addView(chat); form.addView(vision); form.addView(image)
        if (local) {
            chat.isChecked = true; chat.isEnabled = false
        }

        val split = android.widget.Switch(this).apply {
            visibility = if (local) View.GONE else View.VISIBLE
            text = "Отдельная копия для режима"
            isChecked = current?.splitModes ?: true
            setTextColor(Th.fg(0xFFFFFFFF.toInt()))
            textSize = 13.5f
            setPadding(0, dp(8), 0, dp(2))
        }
        form.addView(split)
        form.addView(TextView(this).apply {
            visibility = if (local) View.GONE else View.VISIBLE
            text = "Вкл — для vision и изображений в списках появится отдельная запись («Имя · Vision», «Имя · Image»), основная останется чатом. Выкл — режимы включаются прямо на этой модели."
            setTextColor(Th.fg(0xFF777777.toInt()))
            textSize = 11.5f
            setPadding(0, 0, 0, dp(6))
        })
        var api = current?.imageApi ?: ModelModes.API_OPENAI
        val apiRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val apiLabel = TextView(this).apply {
            setTextColor(Th.fg(0xFF8AB4F8.toInt()))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(6))
        }
        fun refreshApi() {
            apiLabel.text = "Протокол картинок: " + if (api == ModelModes.API_A1111)
                "Automatic1111 / Forge (/sdapi/v1/txt2img) — нажмите, чтобы сменить"
            else "OpenAI-совместимый (/images/generations) — нажмите, чтобы сменить"
        }
        apiLabel.setOnClickListener {
            api = if (api == ModelModes.API_A1111) ModelModes.API_OPENAI else ModelModes.API_A1111
            refreshApi()
        }
        refreshApi()
        apiRow.addView(apiLabel)
        form.addView(apiRow)
        val imgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (local) {
            imgBox.addView(TextView(this).apply {
                text = "Нативный движок GGUF (llama.cpp) не умеет рисовать картинки. Укажите адрес отдельного сервера изображений в сети или на этом устройстве (Automatic1111 / Forge / SD.Next, LM Studio, LocalAI): в списке изображений появится запись «Имя · Image»."
                setTextColor(Th.fg(0xFFE0A040.toInt()))
                textSize = 11.5f
                setPadding(0, 0, 0, dp(6))
            })
        }
        val imgUrlField = dialogField("Адрес сервера изображений (например http://192.168.1.5:7860)").apply {
            setText(current?.imageUrl.orEmpty())
            visibility = if (local) View.VISIBLE else View.GONE
        }
        val imgModelField = dialogField("Модель/чекпойнт на сервере (необязательно)").apply {
            setText(current?.imageModel.orEmpty())
            visibility = if (local) View.VISIBLE else View.GONE
        }
        val imgSizeField = dialogField("Размер, например 512x512 (необязательно)").apply {
            setText(current?.imageSize.orEmpty())
        }
        val imgStepsField = dialogField("Шаги, Automatic1111 (необязательно, по умолчанию 20)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if ((current?.imageSteps ?: 0) > 0) setText(current!!.imageSteps.toString())
        }
        val imgNegField = dialogField("Негативный промпт, Automatic1111 (необязательно)").apply {
            setText(current?.imageNegative.orEmpty())
        }
        imgBox.addView(imgUrlField); imgBox.addView(imgModelField); imgBox.addView(imgSizeField)
        imgBox.addView(imgStepsField); imgBox.addView(imgNegField)
        imgBox.visibility = if (image.isChecked) View.VISIBLE else View.GONE
        form.addView(imgBox)
        apiRow.visibility = if (image.isChecked) View.VISIBLE else View.GONE
        image.setOnCheckedChangeListener { _, c ->
            apiRow.visibility = if (c) View.VISIBLE else View.GONE
            imgBox.visibility = if (c) View.VISIBLE else View.GONE
        }
        imageOptsReader = {
            ImageOpts(
                url = imgUrlField.text.toString().trim(),
                model = imgModelField.text.toString().trim(),
                size = imgSizeField.text.toString().trim(),
                steps = imgStepsField.text.toString().trim().toIntOrNull()?.coerceIn(0, 150) ?: 0,
                negative = imgNegField.text.toString().trim()
            )
        }
        form.addView(TextView(this).apply {
            visibility = if (local) View.GONE else View.VISIBLE
            text = "Модели на самом устройстве (GGUF) поддерживают Чат и Vision; генерация изображений у них — через отдельный сервер изображений."
            setTextColor(Th.fg(0xFF777777.toInt()))
            textSize = 11.5f
            setPadding(0, 0, 0, dp(6))
        })
        return {
            val set = mutableListOf<String>()
            if (chat.isChecked) set.add(ModelModes.CHAT)
            if (vision.isChecked) set.add(ModelModes.VISION)
            if (image.isChecked) set.add(ModelModes.IMAGE)
            Triple(ModelModes.join(set), split.isChecked, api)
        }
    }

    private fun dialogField(hint: String, password: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Th.fg(0xFF666666.toInt()))
        setTextColor(Th.fg(0xFFFFFFFF.toInt()))
        background = getDrawable(R.drawable.bg_input_field)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (password) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            attachEyeToggle()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        layoutParams = lp
    }

    private fun showAddByLinkDialog() = showModelFormDialog(existing = null)

    private fun showEditModelDialog(existing: CustomModel) =
        if (existing.source == "local") showEditLocalModelDialog(existing) else showModelFormDialog(existing)

    private fun showEditLocalModelDialog(existing: CustomModel) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val nameField = dialogField("Название модели").apply { setText(existing.name) }
        form.addView(nameField)

        val ctxField = dialogField("Размер контекста (n_ctx)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existing.contextSize.toString())
        }
        form.addView(ctxField)

        val threadsField = dialogField("Число потоков CPU").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existing.threads.toString())
        }
        form.addView(threadsField)

        val gpuField = dialogField("Слоёв на GPU (0 = только CPU)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(existing.gpuLayers.toString())
        }
        form.addView(gpuField)

        form.addView(TextView(this).apply {
            text = "Формат чат-разметки промпта:"
            setTextColor(Th.fg(0xFFAAAAAA.toInt()))
            textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        })
        var selectedFormat = existing.promptFormat.ifBlank { "chatml" }
        val chips = mutableListOf<TextView>()
        fun styleChip(chip: TextView, selected: Boolean) {
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Th.bg(if (selected) 0xFF8AB4F8.toInt() else 0xFF2A2A32.toInt()))
                cornerRadius = dp(16).toFloat()
            }
            chip.setTextColor(if (selected) Th.fg(0xFF101114.toInt()) else Th.fg(0xFFCCCCCC.toInt()))
        }
        val formatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("chatml" to "ChatML (Qwen/GLM/Yi…)", "llama3" to "Llama-3.x", "phi" to "Phi-3/4", "gemma" to "Gemma", "plain" to "Plain").forEach { (value, label) ->
            val chip = TextView(this).apply {
                text = label
                tag = value
                textSize = 12f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                applyPressAnimation(PressAnimStyle.SOFT)
            }
            styleChip(chip, value == selectedFormat)
            chip.setOnClickListener {
                selectedFormat = value
                chips.forEach { c -> styleChip(c, c.tag == value) }
            }
            chips.add(chip)
            formatRow.addView(chip)
        }
        form.addView(formatRow)

        val tokenLimitField = dialogField("Лимит токенов на ответ (необязательно)").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (existing.tokenLimit > 0) setText(existing.tokenLimit.toString())
        }
        form.addView(tokenLimitField)
        val readModesL = addModesSection(form, existing, local = true)

        styledPanelDialog(
            title = "Локальная модель — настройки",
            contentView = form,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Сохранить") {
                    val name = nameField.text.toString().trim().ifBlank { existing.name }
                    val ctx = ctxField.text.toString().trim().toIntOrNull()?.coerceIn(256, 32768) ?: existing.contextSize
                    val threads = threadsField.text.toString().trim().toIntOrNull()?.coerceIn(1, 16) ?: existing.threads
                    val gpuLayers = gpuField.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: existing.gpuLayers
                    val tokenLimit = tokenLimitField.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val ioL = imageOptsReader?.invoke()
                    val mtL = readModesL()
                    val modesL = localModesChecked(mtL.first, ioL)
                    ModelsStore.update(
                        this, existing,
                        existing.copy(
                            name = name, contextSize = ctx, threads = threads,
                            gpuLayers = gpuLayers, promptFormat = selectedFormat, tokenLimit = tokenLimit,
                            modes = modesL, splitModes = false,
                            imageApi = mtL.third, imageUrl = ioL?.url.orEmpty(), imageModel = ioL?.model.orEmpty(),
                            imageSize = ioL?.size.orEmpty(), imageSteps = ioL?.steps ?: 0, imageNegative = ioL?.negative.orEmpty()
                        )
                    )
                    Toast.makeText(this, Loc.t("Модель обновлена: $name"), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            )
        )
    }

    private fun showHfFilePrefillDialog(fileName: String, guessedId: String, guessedName: String) =
        showModelFormDialog(
            existing = null,
            prefillId = guessedId,
            prefillName = guessedName,
            newSource = "file",
            hintText = Loc.t("Файл «$fileName» — это веса не в формате GGUF, приложение не может " +
                "запустить их прямо на устройстве. Поднимите сервер с этой моделью (например, " +
                "свой inference-сервис с OpenAI-совместимым API) и укажите его адрес ниже как " +
                "ссылку провайдера. Если найдёте GGUF-версию этой модели (часто есть " +
                "квантованные .gguf-сборки на Hugging Face) — импортируйте её вместо этого " +
                "файла, тогда модель запустится прямо на телефоне.")
        )

    private fun showLocalModelDialog(uri: Uri, fileName: String, guessedName: String) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        form.addView(TextView(this).apply {
            text = "Модель запустится прямо на телефоне через встроенный движок llama.cpp — " +
                "без интернета и без своего сервера. Чем больше файл (параметров/квантование), " +
                "тем больше ОЗУ и времени на ответ ей понадобится — на телефоне разумно " +
                "начинать с моделей 1–4B параметров в Q4-квантовании."
            setTextColor(Th.fg(0xFF9AA79A.toInt()))
            textSize = 12.5f
            setPadding(0, 0, 0, dp(12))
        })

        val nameField = dialogField("Название модели")
        nameField.setText(guessedName)
        form.addView(nameField)

        val ctxField = dialogField("Размер контекста (n_ctx), напр. 2048")
        ctxField.inputType = InputType.TYPE_CLASS_NUMBER
        ctxField.setText("2048")
        form.addView(ctxField)

        val threadsDefault = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val threadsField = dialogField("Число потоков CPU, напр. $threadsDefault")
        threadsField.inputType = InputType.TYPE_CLASS_NUMBER
        threadsField.setText(threadsDefault.toString())
        form.addView(threadsField)

        val gpuField = dialogField("Слоёв на GPU (0 = только CPU — надёжнее)")
        gpuField.inputType = InputType.TYPE_CLASS_NUMBER
        gpuField.setText("0")
        form.addView(gpuField)

        form.addView(TextView(this).apply {
            text = "Формат чат-разметки промпта — выберите, что ближе к семейству модели:"
            setTextColor(Th.fg(0xFFAAAAAA.toInt()))
            textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        })
        var selectedFormat = "chatml"
        val chips = mutableListOf<TextView>()
        fun styleChip(chip: TextView, selected: Boolean) {
            chip.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Th.bg(if (selected) 0xFF8AB4F8.toInt() else 0xFF2A2A32.toInt()))
                cornerRadius = dp(16).toFloat()
            }
            chip.setTextColor(if (selected) Th.fg(0xFF101114.toInt()) else Th.fg(0xFFCCCCCC.toInt()))
        }
        val formatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("chatml" to "ChatML (Qwen/GLM/Yi…)", "llama3" to "Llama-3.x", "phi" to "Phi-3/4", "gemma" to "Gemma", "plain" to "Plain").forEach { (value, label) ->
            val chip = TextView(this).apply {
                text = label
                tag = value
                textSize = 12f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                applyPressAnimation(PressAnimStyle.SOFT)
            }
            styleChip(chip, value == selectedFormat)
            chip.setOnClickListener {
                selectedFormat = value
                chips.forEach { c -> styleChip(c, c.tag == value) }
            }
            chips.add(chip)
            formatRow.addView(chip)
        }
        form.addView(formatRow)

        val tokenLimitField = dialogField("Лимит токенов на ответ (необязательно)")
        tokenLimitField.inputType = InputType.TYPE_CLASS_NUMBER
        form.addView(tokenLimitField)
        val readModesNew = addModesSection(form, null, local = true)

        styledPanelDialog(
            title = "Локальная модель (GGUF)",
            contentView = form,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton("Добавить") {
                    val name = nameField.text.toString().trim().ifBlank { guessedName }
                    val ctx = ctxField.text.toString().trim().toIntOrNull()?.coerceIn(256, 32768) ?: 2048
                    val threads = threadsField.text.toString().trim().toIntOrNull()?.coerceIn(1, 16) ?: threadsDefault
                    val gpuLayers = gpuField.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val tokenLimit = tokenLimitField.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0

                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (e: Exception) {
                        Toast.makeText(
                            this,
                            Loc.t("Не удалось закрепить доступ к файлу (${e.message}). После перезапуска приложения модель может перестать открываться — тогда просто добавьте её заново."),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    val mtN = readModesNew()
                    val ioN = imageOptsReader?.invoke()
                    val modesN = localModesChecked(mtN.first, ioN)

                    val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "local" }
                    val id = slug + "-" + (uri.toString().hashCode().toUInt().toString(16).take(6))

                    ModelsStore.add(
                        this,
                        CustomModel(
                            id = id,
                            name = name,
                            providerUrl = "",
                            apiKey = "",
                            source = "local",
                            tokenLimit = tokenLimit,
                            localUri = uri.toString(),
                            contextSize = ctx,
                            threads = threads,
                            gpuLayers = gpuLayers,
                            promptFormat = selectedFormat,
                            modes = modesN,
                            splitModes = false,
                            imageApi = mtN.third,
                            imageUrl = ioN?.url.orEmpty(),
                            imageModel = ioN?.model.orEmpty(),
                            imageSize = ioN?.size.orEmpty(),
                            imageSteps = ioN?.steps ?: 0,
                            imageNegative = ioN?.negative.orEmpty()
                        )
                    )
                    Toast.makeText(this, Loc.t("Модель добавлена: $name (запускается на устройстве)"), Toast.LENGTH_SHORT).show()
                    refresh()
                }
            )
        )
    }

    private fun showModelFormDialog(
        existing: CustomModel?,
        prefillId: String = "",
        prefillName: String = "",
        newSource: String = "link",
        hintText: String? = null
    ) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        if (hintText != null) {
            form.addView(TextView(this).apply {
                text = hintText
                setTextColor(Th.fg(0xFF9AA79A.toInt()))
                textSize = 12.5f
                setPadding(0, 0, 0, dp(12))
            })
        }
        val idField = dialogField("ID модели (например gpt-4o-mini)")
        val urlField = dialogField("Провайдер — ссылка на API (https://...)")
        val keyField = dialogField("API-ключ модели (необязательно)", password = true)
        val nameField = dialogField("Название (необязательно)")
        val tokenLimitField = dialogField("Лимит токенов на ответ (необязательно)")
        tokenLimitField.inputType = InputType.TYPE_CLASS_NUMBER
        if (existing != null) {
            idField.setText(existing.id)
            urlField.setText(existing.providerUrl)
            keyField.setText(existing.apiKey)
            nameField.setText(existing.name)
            if (existing.tokenLimit > 0) tokenLimitField.setText(existing.tokenLimit.toString())
        } else {
            if (prefillId.isNotBlank()) idField.setText(prefillId)
            if (prefillName.isNotBlank()) nameField.setText(prefillName)
        }
        form.addView(idField)
        form.addView(urlField)
        form.addView(keyField)
        form.addView(nameField)
        form.addView(tokenLimitField)
        form.addView(TextView(this).apply {
            text = "Лимит токенов ограничивает длину ОДНОГО ответа именно этой модели " +
                "(max_tokens в запросе) и имеет приоритет над общим лимитом из Экономики. " +
                "Оставьте пустым, если ограничивать не нужно."
            setTextColor(Th.fg(0xFF777777.toInt()))
            textSize = 11.5f
            setPadding(0, dp(2), 0, dp(6))
        })
        val readModes = if (existing == null || existing.source != "local") addModesSection(form, existing) else null

        styledPanelDialog(
            title = if (existing != null) "Редактировать модель" else "Добавить модель",
            contentView = form,
            buttons = listOf(
                StyledDialogButton("Отмена") {},
                StyledDialogButton(if (existing != null) "Сохранить" else "Добавить") {
                    val id = idField.text.toString().trim()
                    val url = urlField.text.toString().trim()
                    val key = keyField.text.toString().trim()
                    val name = nameField.text.toString().trim().ifBlank { id }
                    val tokenLimit = tokenLimitField.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    if (id.isBlank() || url.isBlank()) {
                        Toast.makeText(this, Loc.t("Нужны хотя бы ID модели и ссылка провайдера. Ключ можно оставить пустым, если он задан у провайдера."), Toast.LENGTH_LONG).show()
                    } else if (existing != null) {
                        val mt = readModes?.invoke()
                        val io = if (readModes != null) imageOptsReader?.invoke() else null
                        ModelsStore.update(this, existing, CustomModel(id, name, url, key, existing.source, tokenLimit,
                            modes = mt?.first ?: existing.modes, splitModes = mt?.second ?: existing.splitModes, imageApi = mt?.third ?: existing.imageApi,
                            imageSize = io?.size ?: existing.imageSize, imageSteps = io?.steps ?: existing.imageSteps,
                            imageNegative = io?.negative ?: existing.imageNegative))
                        Toast.makeText(this, Loc.t("Модель обновлена: $name"), Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        val mt = readModes?.invoke()
                        val io = if (readModes != null) imageOptsReader?.invoke() else null
                        ModelsStore.add(this, CustomModel(id, name, url, key, newSource, tokenLimit,
                            modes = mt?.first ?: ModelModes.CHAT, splitModes = mt?.second ?: true, imageApi = mt?.third ?: ModelModes.API_OPENAI,
                            imageSize = io?.size.orEmpty(), imageSteps = io?.steps ?: 0, imageNegative = io?.negative.orEmpty()))
                        Toast.makeText(this, Loc.t("Модель добавлена: $name"), Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            )
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != importReq || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importArchive(uri)
    }

    private fun readLimitedBytes(uri: Uri, maxBytes: Int): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { ins ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val r = ins.read(buf, total, maxBytes - total)
                if (r <= 0) break
                total += r
            }
            buf.copyOf(total)
        }
    } catch (_: Exception) { null }

    private fun importArchive(uri: Uri) {
        try {
            var name = uri.lastPathSegment ?: "archive"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { if (it.isNotBlank()) name = it }
            }

            val headBytes = readLimitedBytes(uri, 2 * 1024 * 1024)
            if (ModelsStore.isGguf(name, headBytes)) {
                val baseName = name.substringBeforeLast('.').ifBlank { name }
                val ggufName = headBytes?.let { ModelsStore.ggufModelName(it) }
                showLocalModelDialog(uri, fileName = name, guessedName = ggufName ?: baseName)
                return
            }
            if (ModelsStore.isHfModelFile(name)) {
                val baseName = name.substringBeforeLast('.').ifBlank { name }
                showHfFilePrefillDialog(name, guessedId = baseName, guessedName = baseName)
                return
            }

            val manifests = mutableListOf<String>()
            val directText = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Exception) { null }
            val looksLikeJson = directText?.trim()?.let { it.startsWith("{") || it.startsWith("[") } == true
            when {
                looksLikeJson -> {
                    manifests.add(directText!!)
                }
                name.endsWith(".rar", true) -> {
                    val tmp = java.io.File(cacheDir, "import_${System.currentTimeMillis()}.rar")
                    contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                    val arch = Archive(tmp)
                    try {
                        for (hdr in arch.fileHeaders) {
                            val fn = try { hdr.fileNameString } catch (_: Exception) { "" }
                            if (fn.substringAfterLast('/').lowercase() in setOf("model.json", "manifest.json")) {
                                val bytes = java.io.ByteArrayOutputStream().also { out -> arch.extractFile(hdr, out) }.toByteArray()
                                manifests.add(String(bytes, Charsets.UTF_8))
                            }
                        }
                    } finally {
                        try { arch.close() } catch (_: Exception) {}
                        tmp.delete()
                    }
                }
                else -> {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        ZipInputStream(BufferedInputStream(ins)).use { zis ->
                            var e = zis.nextEntry
                            while (e != null) {
                                if (!e.isDirectory && e.name.substringAfterLast('/').lowercase() in setOf("model.json", "manifest.json")) {
                                    manifests.add(zis.readBytes().toString(Charsets.UTF_8))
                                }
                                zis.closeEntry()
                                e = zis.nextEntry
                            }
                        }
                    }
                }
            }
            if (manifests.isEmpty()) {
                Toast.makeText(this, Loc.t("В архиве не найден model.json / manifest.json"), Toast.LENGTH_LONG).show()
                return
            }
            var added = 0
            for (m in manifests) {
                val parsed = ModelsStore.parseManifest(m)
                for (cm in parsed) {
                    ModelsStore.add(this, cm)
                    added++
                }
            }
            if (added == 0) {
                Toast.makeText(this, Loc.t("Не удалось прочитать модели из manifest — проверьте поля id/providerUrl; apiKey теперь необязателен."), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, Loc.t("Импортировано моделей: $added"), Toast.LENGTH_LONG).show()
                refresh()
            }
        } catch (e: Exception) {
            Toast.makeText(this, Loc.t("Импорт: ${e.message}"), Toast.LENGTH_LONG).show()
        }
    }
}
