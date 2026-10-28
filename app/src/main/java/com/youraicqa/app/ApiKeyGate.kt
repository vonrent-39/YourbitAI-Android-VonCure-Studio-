package com.youraicqa.app

import android.content.Context

object ApiKeyGate {

    const val PROVIDER_CUSTOM = "custom"

    const val PROVIDER_LOCAL = "local"

    const val PROVIDER_SERVER = "server"

    const val PROVIDER_PLUGIN = "plugin"

    val BUILTIN_PROVIDERS = listOf(
        "openrouter", "hf", "groq", "gemini", "cloudflare", "llm7", "pollinations", "mistral", "cohere",
        "openai", "qwen", "deepseek", "anthropic", "nvidia"
    )
    private val KNOWN_PROVIDERS = BUILTIN_PROVIDERS.toSet()

    fun keyOptional(provider: String): Boolean = provider == "llm7"

    fun hasOwnKey(context: Context, provider: String): Boolean {
        if (provider == PROVIDER_CUSTOM || provider == PROVIDER_LOCAL || provider == PROVIDER_SERVER || provider == PROVIDER_PLUGIN) return true
        if (provider !in KNOWN_PROVIDERS) return false
        val stored = prefs(context).getString(provider, "")?.trim().orEmpty()
        return stored.isNotBlank()
    }

    fun rememberLastUsedProvider(context: Context, provider: String) {
        prefs(context).edit().putString(KEY_LAST_PROVIDER, provider).apply()
    }

    fun lastUsedProvider(context: Context): String? = prefs(context).getString(KEY_LAST_PROVIDER, null)

    fun providerLabel(provider: String?): String = when (provider) {
        "openrouter" -> "OpenRouter"
        "groq" -> "Groq"
        "gemini" -> "Google AI Studio"
        "cloudflare" -> "Cloudflare Workers AI"
        "hf" -> "Hugging Face"
        "llm7" -> "LLM7.io"
        "pollinations" -> "Pollinations"
        "mistral" -> "Mistral AI"
        "cohere" -> "Cohere"
        "openai" -> "OpenAI"
        "qwen" -> "Qwen (Alibaba Cloud)"
        "deepseek" -> "DeepSeek"
        "anthropic" -> "Anthropic (Claude)"
        "nvidia" -> "NVIDIA NIM"
        PROVIDER_CUSTOM -> "своя модель"
        PROVIDER_LOCAL -> "локальная модель (на устройстве)"
        PROVIDER_SERVER -> "свой сервер"
        PROVIDER_PLUGIN -> "провайдер плагина"
        else -> "текущая модель"
    }

    private const val KEY_LAST_PROVIDER = "last_used_provider"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("nanoai", Context.MODE_PRIVATE)
}
