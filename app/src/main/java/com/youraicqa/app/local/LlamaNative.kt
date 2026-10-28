package com.youraicqa.app.local

object LlamaNative {
    private var libraryLoaded = false
    private var libraryError: Throwable? = null

    init {
        try {
            System.loadLibrary("youraicqa_llama")
            libraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            libraryError = e
        }
    }

    val isAvailable: Boolean get() = libraryLoaded
    val loadError: Throwable? get() = libraryError

    external fun nativeBackendInit()
    external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        callback: TokenCallback
    ): String
    external fun nativeCancel(handle: Long)
    external fun nativeFreeModel(handle: Long)

    fun interface TokenCallback {
        fun onToken(piece: String)
    }
}
