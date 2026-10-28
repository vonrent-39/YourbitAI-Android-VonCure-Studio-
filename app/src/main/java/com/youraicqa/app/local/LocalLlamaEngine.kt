package com.youraicqa.app.local

import android.content.Context
import android.net.Uri
import com.youraicqa.app.ModelsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

data class LocalModelConfig(
    val id: String,
    val uri: String,
    val contextSize: Int,
    val threads: Int,
    val gpuLayers: Int,
    val promptFormat: String
)

object LocalLlamaEngine {
    private val execDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-llama").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var backendReady = false
    private var loadedModelId: String? = null
    private var loadedConfig: LocalModelConfig? = null
    private var handle: Long = 0L

    const val CTX_OVERFLOW_MARKER = "\u0001CTX_OVERFLOW"

    val isAvailable: Boolean get() = LlamaNative.isAvailable

    private fun ensureBackend() {
        if (!backendReady) {
            LlamaNative.nativeBackendInit()
            backendReady = true
        }
    }

    private suspend fun resolvePath(context: Context, uriString: String, modelId: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(uriString)
                val expectedSize = try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (_: Exception) { -1L }

                val dir = File(context.filesDir, "local_models").apply { mkdirs() }
                val target = File(dir, "$modelId.gguf")

                val alreadyOk = target.exists() && target.length() > 0 &&
                    (expectedSize <= 0 || target.length() == expectedSize) &&
                    looksLikeGgufFile(target)
                if (!alreadyOk) {
                    target.delete()
                    val tmp = File(dir, "$modelId.gguf.part")
                    var copied = 0L
                    try {
                        val ins = context.contentResolver.openInputStream(uri)
                            ?: return@withContext Result.failure(Exception(
                                "система вернула пустой поток для файла (openInputStream == null) — " +
                                    "похоже, доступ к файлу потерян; удалите эту модель в «Своих моделях» и добавьте заново"
                            ))
                        ins.use { input ->
                            tmp.outputStream().use { output ->
                                val buf = ByteArray(1 shl 20)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r <= 0) break
                                    output.write(buf, 0, r)
                                    copied += r
                                }
                            }
                        }
                    } catch (e: Exception) {
                        tmp.delete()
                        return@withContext Result.failure(Exception(
                            "копирование файла прервалось на ${copied} байт (${e.javaClass.simpleName}: ${e.message}) — " +
                                "источник (например, облачное хранилище) мог оборвать поток. Попробуйте ещё раз, " +
                                "или сначала сохраните файл в обычную локальную папку на телефоне и выберите его оттуда"
                        ))
                    }
                    if (expectedSize > 0 && copied != expectedSize) {
                        tmp.delete()
                        return@withContext Result.failure(Exception(
                            "файл скопирован не полностью: получено $copied из $expectedSize байт. " +
                                "Обычно это значит, что источник (облачное хранилище/провайдер файла) отдал файл " +
                                "не целиком — сам GGUF-файл при этом может быть исправен. Попробуйте снова, " +
                                "или сначала сохраните его как обычный локальный файл (например, в папку «Загрузки») " +
                                "и выберите его оттуда"
                        ))
                    }
                    if (!looksLikeGgufFile(tmp)) {
                        tmp.delete()
                        return@withContext Result.failure(Exception(
                            "скопированный файл не начинается с сигнатуры GGUF — это не GGUF-файл " +
                                "(или он повреждён/это не тот файл)"
                        ))
                    }
                    tmp.renameTo(target)
                }
                Result.success(target.absolutePath)
            } catch (e: SecurityException) {
                Result.failure(Exception(
                    "нет доступа к файлу (SecurityException: ${e.message}). Обычно значит, что доступ, " +
                        "выданный при добавлении модели, не сохранился между запусками приложения — " +
                        "удалите эту модель в «Своих моделях» и добавьте заново"
                ))
            } catch (e: Exception) {
                Result.failure(Exception("не удалось скопировать файл (${e.javaClass.simpleName}: ${e.message})"))
            }
        }

    private fun looksLikeGgufFile(f: File): Boolean {
        if (!f.exists() || f.length() < 4) return false
        return try {
            f.inputStream().use { ins ->
                val head = ByteArray(4)
                if (ins.read(head) != 4) return false
                ModelsStore.looksLikeGguf(head)
            }
        } catch (_: Exception) { false }
    }

    sealed class LoadOutcome {
        object Ok : LoadOutcome()
        data class Failed(val reason: String) : LoadOutcome()
    }

    suspend fun ensureLoaded(context: Context, config: LocalModelConfig): LoadOutcome = withContext(execDispatcher) {
        if (!LlamaNative.isAvailable) {
            return@withContext LoadOutcome.Failed(
                "нативный движок llama.cpp не собран в этой сборке приложения " +
                    "(LlamaNative.isAvailable == false) — см. app/src/main/cpp/CMakeLists.txt"
            )
        }
        if (loadedModelId == config.id && handle != 0L && loadedConfig?.let {
                it.contextSize == config.contextSize && it.threads == config.threads &&
                    it.gpuLayers == config.gpuLayers
            } == true) return@withContext LoadOutcome.Ok
        unloadInternal()
        ensureBackend()
        val pathResult = resolvePath(context, config.uri, config.id)
        val path = pathResult.getOrElse { return@withContext LoadOutcome.Failed(it.message ?: "неизвестная ошибка доступа к файлу") }
        val threads = config.threads.coerceIn(1, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        val h = LlamaNative.nativeLoadModel(path, config.contextSize, threads, config.gpuLayers)
        if (h == 0L) {
            return@withContext LoadOutcome.Failed(
                "сам llama.cpp отклонил файл при загрузке (nativeLoadModel вернул 0) — возможно, " +
                    "файл повреждён, скачан не до конца, не в формате GGUF, или для контекста " +
                    "n_ctx=${config.contextSize} не хватает памяти. Попробуйте уменьшить размер " +
                    "контекста в настройках модели или взять модель меньшего размера/квантования"
            )
        }
        handle = h
        loadedModelId = config.id
        loadedConfig = config
        LoadOutcome.Ok
    }

    private fun unloadInternal() {
        if (handle != 0L) {
            LlamaNative.nativeFreeModel(handle)
            handle = 0L
        }
        loadedModelId = null
        loadedConfig = null
    }

    suspend fun unload() = withContext(execDispatcher) { unloadInternal() }

    fun requestCancel() {
        if (handle != 0L) LlamaNative.nativeCancel(handle)
    }

    suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float = 0.8f,
        topP: Float = 0.95f,
        repeatPenalty: Float = 1.1f,
        onStarted: () -> Unit = {},
        onToken: (String) -> Unit = {}
    ): String = withContext(execDispatcher) {
        if (handle == 0L) throw IllegalStateException("Локальная модель не загружена")
        onStarted()
        LlamaNative.nativeGenerate(
            handle, prompt, maxTokens, temperature, topP, repeatPenalty,
            LlamaNative.TokenCallback { piece -> onToken(piece) }
        )
    }
}
