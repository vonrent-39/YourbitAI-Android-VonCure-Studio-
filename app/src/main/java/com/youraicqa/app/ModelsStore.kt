package com.youraicqa.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CustomModel(
    val id: String,
    val name: String,
    val providerUrl: String,
    val apiKey: String,
    val source: String = "link",
    val tokenLimit: Int = 0,
    val localUri: String = "",
    val contextSize: Int = 2048,
    val threads: Int = 4,
    val gpuLayers: Int = 0,
    val promptFormat: String = "chatml",
    val modes: String = ModelModes.CHAT,
    val splitModes: Boolean = true,
    val imageApi: String = ModelModes.API_OPENAI,
    val imageUrl: String = "",
    val imageModel: String = "",
    val imageSize: String = "",
    val imageSteps: Int = 0,
    val imageNegative: String = ""
)

object ModelModes {
    const val CHAT = "chat"
    const val VISION = "vision"
    const val IMAGE = "image"
    const val API_OPENAI = "openai"
    const val API_A1111 = "a1111"
    val ALL = listOf(CHAT, VISION, IMAGE)

    fun parse(raw: String): Set<String> {
        val set = raw.split(',').map { it.trim().lowercase() }.filter { it in ALL }.toMutableSet()
        if (set.isEmpty()) set.add(CHAT)
        return set
    }

    fun join(modes: Collection<String>): String =
        ALL.filter { it in modes }.ifEmpty { listOf(CHAT) }.joinToString(",")

    fun label(mode: String): String = when (mode) {
        VISION -> "Vision"
        IMAGE -> "Image"
        else -> "Chat"
    }

    fun expand(cm: CustomModel): List<MainActivity.Model> {
        val builtinProvider = ModelsStore.builtinProviderOf(cm.providerUrl)
        if (builtinProvider != null && cm.source != "local") {
            return listOf(MainActivity.Model(
                name = cm.name, provider = builtinProvider, id = cm.id, badge = "USR",
                desc = "Своя модель — ${ApiKeyGate.providerLabel(builtinProvider)}", accent = 0xFFB0BEC5.toInt(),
                isVision = VISION in parse(cm.modes)
            ))
        }
        val base = MainActivity.Model(
            name = cm.name, provider = "custom", id = cm.id, badge = "USR",
            desc = "Своя модель (${cm.providerUrl})", accent = 0xFFB0BEC5.toInt(), customProviderUrl = cm.providerUrl
        )
        val modes = parse(cm.modes)
        if (cm.source == "local") {
            val localChat = MainActivity.Model(
                name = cm.name, provider = "local", id = cm.id, badge = "GGUF",
                desc = "Локальная модель — работает на устройстве, без интернета",
                accent = 0xFFFFD54F.toInt(), isLocal = true, isVision = VISION in modes
            )
            if (IMAGE in modes && cm.imageUrl.isNotBlank()) {
                return listOf(localChat, MainActivity.Model(
                    name = cm.name + " · Image", provider = "custom", id = cm.imageModel.ifBlank { "default" },
                    badge = "IMG", desc = "Генерация изображений (${cm.imageUrl})", accent = 0xFFCE93D8.toInt(),
                    customProviderUrl = cm.imageUrl, isImage = true, modeTag = IMAGE, imageApi = cm.imageApi,
                    imageSize = cm.imageSize, imageSteps = cm.imageSteps, imageNegative = cm.imageNegative
                ))
            }
            return listOf(localChat)
        }
        if (modes == setOf(CHAT)) return listOf(base)
        val out = ArrayList<MainActivity.Model>()
        val chatOn = CHAT in modes
        val visionOn = VISION in modes
        val imageOn = IMAGE in modes
        if (chatOn || (!imageOn && visionOn)) {
            out.add(base.copy(isVision = visionOn && !cm.splitModes || (!chatOn && visionOn),
                modeTag = if (!chatOn && visionOn) VISION else "",
                badge = if (visionOn && !cm.splitModes) "VIS" else "USR"))
        }
        if (visionOn && cm.splitModes && chatOn) {
            out.add(base.copy(name = cm.name + " · Vision", isVision = true, modeTag = VISION, badge = "VIS",
                desc = "Распознавание изображений (${cm.providerUrl})", accent = 0xFF64B5F6.toInt()))
        }
        if (imageOn) {
            out.add(base.copy(name = if (cm.splitModes) cm.name + " · Image" else cm.name, isImage = true,
                modeTag = IMAGE, imageApi = cm.imageApi, badge = "IMG",
                imageSize = cm.imageSize, imageSteps = cm.imageSteps, imageNegative = cm.imageNegative,
                desc = "Генерация изображений (${cm.providerUrl})", accent = 0xFFCE93D8.toInt()))
        }
        return out.ifEmpty { listOf(base) }
    }
}

object ModelsStore {
    private const val PREFS = "nanoai"
    private const val KEY = "custom_models"

    const val BUILTIN_PREFIX = "builtin:"

    fun builtinMarker(provider: String): String = BUILTIN_PREFIX + provider

    fun builtinProviderOf(providerUrl: String): String? {
        if (!providerUrl.startsWith(BUILTIN_PREFIX)) return null
        val name = providerUrl.removePrefix(BUILTIN_PREFIX).trim().lowercase()
        return name.takeIf { it in ApiKeyGate.BUILTIN_PROVIDERS }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<CustomModel> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CustomModel(
                    id = o.optString("id"),
                    name = o.optString("name").ifBlank { o.optString("id") },
                    providerUrl = o.optString("providerUrl"),
                    apiKey = o.optString("apiKey"),
                    source = o.optString("source", "link"),
                    tokenLimit = o.optInt("tokenLimit", 0),
                    localUri = o.optString("localUri"),
                    contextSize = o.optInt("contextSize", 2048),
                    threads = o.optInt("threads", 4),
                    gpuLayers = o.optInt("gpuLayers", 0),
                    promptFormat = o.optString("promptFormat", "chatml"),
                    modes = ModelModes.join(ModelModes.parse(o.optString("modes", ModelModes.CHAT))),
                    splitModes = o.optBoolean("splitModes", true),
                    imageApi = o.optString("imageApi", ModelModes.API_OPENAI).ifBlank { ModelModes.API_OPENAI },
                    imageUrl = o.optString("imageUrl"),
                    imageModel = o.optString("imageModel"),
                    imageSize = o.optString("imageSize"),
                    imageSteps = o.optInt("imageSteps", 0),
                    imageNegative = o.optString("imageNegative")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<CustomModel>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("providerUrl", m.providerUrl)
                    .put("apiKey", m.apiKey)
                    .put("source", m.source)
                    .put("tokenLimit", m.tokenLimit)
                    .put("localUri", m.localUri)
                    .put("contextSize", m.contextSize)
                    .put("threads", m.threads)
                    .put("gpuLayers", m.gpuLayers)
                    .put("promptFormat", m.promptFormat)
                    .put("modes", m.modes)
                    .put("splitModes", m.splitModes)
                    .put("imageApi", m.imageApi)
                    .put("imageUrl", m.imageUrl)
                    .put("imageModel", m.imageModel)
                    .put("imageSize", m.imageSize)
                    .put("imageSteps", m.imageSteps)
                    .put("imageNegative", m.imageNegative)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, model: CustomModel) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == model.id && it.providerUrl == model.providerUrl }
        list.add(model)
        save(context, list)
    }

    fun remove(context: Context, model: CustomModel) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == model.id && it.providerUrl == model.providerUrl }
        save(context, list)
    }

    fun update(context: Context, old: CustomModel, updated: CustomModel) {
        val list = load(context).toMutableList()
        list.removeAll { it.id == old.id && it.providerUrl == old.providerUrl }
        list.removeAll { it.id == updated.id && it.providerUrl == updated.providerUrl }
        list.add(updated)
        save(context, list)
    }

    fun parseManifest(text: String): List<CustomModel> {
        fun fromObj(o: JSONObject): CustomModel? {
            val id = o.optString("id").ifBlank { o.optString("model") }
            val url = o.optString("providerUrl").ifBlank { o.optString("provider") }
                .ifBlank { o.optString("url") }.ifBlank { o.optString("base_url") }
            val key = o.optString("apiKey").ifBlank { o.optString("api_key") }.ifBlank { o.optString("key") }
            if (id.isBlank() || url.isBlank()) return null
            val name = o.optString("name").ifBlank { id }
            val tokenLimit = o.optInt("tokenLimit", o.optInt("token_limit", 0))
            val modesRaw = o.opt("modes").let { v ->
                if (v is JSONArray) (0 until v.length()).joinToString(",") { v.optString(it) } else o.optString("modes")
            }
            return CustomModel(
                id, name, url, key, "archive", tokenLimit,
                modes = ModelModes.join(ModelModes.parse(modesRaw)),
                splitModes = o.optBoolean("splitModes", o.optBoolean("split_modes", true)),
                imageApi = o.optString("imageApi", o.optString("image_api", ModelModes.API_OPENAI)).ifBlank { ModelModes.API_OPENAI },
                imageSize = o.optString("imageSize", o.optString("image_size")),
                imageSteps = o.optInt("imageSteps", o.optInt("image_steps", 0)),
                imageNegative = o.optString("imageNegative", o.optString("negative_prompt"))
            )
        }
        return try {
            val trimmed = text.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromObj(it) } }
            } else {
                val o = JSONObject(trimmed)
                listOfNotNull(fromObj(o))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val HF_MODEL_FILE_EXTENSIONS = setOf(
        "gguf", "ggml", "safetensors", "bin", "pt", "pth", "ckpt", "onnx", "npz", "model"
    )

    fun isHfModelFile(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in HF_MODEL_FILE_EXTENSIONS

    fun isGguf(fileName: String, headBytes: ByteArray?): Boolean =
        fileName.substringAfterLast('.', "").lowercase() == "gguf" ||
            (headBytes != null && looksLikeGguf(headBytes))

    fun looksLikeGguf(header: ByteArray): Boolean =
        header.size >= 4 && header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
            header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte()

    fun ggufModelName(bytes: ByteArray): String? {
        try {
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 24 || !looksLikeGguf(bytes)) return null
            buf.position(4)
            buf.int
            buf.long
            val kvCount = buf.long
            fun readGgufString(): String {
                val len = buf.long
                val arr = ByteArray(len.toInt())
                buf.get(arr)
                return String(arr, Charsets.UTF_8)
            }
            fun skipValue(type: Int) {
                when (type) {
                    0, 1, 7 -> buf.get()           // uint8/int8/bool
                    2, 3 -> buf.short               // uint16/int16
                    4, 5, 6 -> buf.int               // uint32/int32/float32
                    10, 11, 12 -> buf.long           // uint64/int64/float64
                    8 -> readGgufString()            // string
                    9 -> {                           // array
                        val elemType = buf.int
                        val n = buf.long
                        for (i in 0 until n) skipValue(elemType)
                    }
                }
            }
            for (i in 0 until kvCount) {
                if (buf.remaining() < 8) return null
                val key = readGgufString()
                if (buf.remaining() < 4) return null
                val type = buf.int
                if (key == "general.name" && type == 8) return readGgufString()
                skipValue(type)
            }
        } catch (_: Exception) {
        }
        return null
    }
}
