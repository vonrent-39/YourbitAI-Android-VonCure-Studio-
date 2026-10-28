package com.youraicqa.app

import android.content.Context
import org.json.JSONArray

object EconomyPrefs {
    private const val PREFS = "nanoai"

    private const val KEY_LIMIT_CONTEXT = "eco_limit_context"
    private const val KEY_GIF_SINGLE_FRAME = "eco_gif_single_frame"
    private const val KEY_DISABLE_PERSONA = "eco_disable_persona_prompt"
    private const val KEY_DISABLE_AUTO_RETRY = "eco_disable_auto_retry"
    private const val KEY_DISABLE_SYSTEM_PROMPT = "eco_disable_system_prompt"
    private const val KEY_DISABLE_MEMORY = "eco_disable_memory_prompt"

    private const val KEY_GLOBAL_TOKEN_LIMIT_ENABLED = "eco_global_token_limit_enabled"
    private const val KEY_GLOBAL_TOKEN_LIMIT_VALUE = "eco_global_token_limit_value"
    const val DEFAULT_GLOBAL_TOKEN_LIMIT = 4096

    private const val KEY_DISABLE_PRESS_ANIM = "eco_disable_press_anim"

    private const val KEY_DISABLE_SUGGESTIONS = "eco_disable_suggestions"
    private const val KEY_DISABLE_AUTO_TITLE = "eco_disable_auto_title"
    private const val KEY_DISABLE_IMAGE_BOOST = "eco_disable_image_prompt_boost"
    private const val KEY_SHORT_SEARCH = "eco_short_search"
    private const val KEY_DISABLE_BLUR = "eco_disable_blur"
    private const val KEY_DISABLE_GLOW = "eco_disable_glow"
    private const val KEY_DISABLE_TRANSITIONS = "eco_disable_transitions"
    private const val KEY_DISABLE_SYNTAX = "eco_disable_syntax_highlight"

    private const val KEY_LIGHT_IMAGES = "eco_light_images"
    private const val KEY_ONE_SEARCH_ROUND = "eco_one_search_round"
    private const val KEY_CAP_REASONING = "eco_cap_reasoning"
    private const val KEY_AGENT_SHORT_RUN = "eco_agent_short_run"
    private const val KEY_AGENT_SHORT_PAGE = "eco_agent_short_page"
    private const val KEY_AGENT_TEXT_ONLY = "eco_agent_text_only"
    private const val KEY_STRIP_OLD_CODE = "eco_strip_old_code"
    private const val KEY_THROTTLE_STREAM = "eco_throttle_stream"
    private const val KEY_DISABLE_PULSE = "eco_disable_pulse"

    private const val KEY_ECONOMY_MODE = "eco_economy_mode"
    private const val KEY_CONTEXT_MESSAGES = "eco_context_messages"
    private const val KEY_TRIM_LONG = "eco_trim_long_messages"
    private const val KEY_CONCISE = "eco_concise_answers"
    private const val KEY_SLOW_SYNC = "eco_slow_model_sync"
    private const val KEY_KEEP_FIRST = "eco_keep_first_message"
    private const val KEY_OLD_MAX_CHARS = "eco_old_message_max_chars"
    private const val KEY_FRESH_MESSAGES = "eco_fresh_messages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val SHORT_CONTEXT_MESSAGES = 12
    const val NORMAL_CONTEXT_MESSAGES = 40

    fun limitContext(context: Context): Boolean = prefs(context).getBoolean(KEY_LIMIT_CONTEXT, false)
    fun setLimitContext(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LIMIT_CONTEXT, v).apply() }
    const val MEMORY_MIN = 2
    const val MEMORY_MAX = 200
    private const val WHOLE_CHAT = 100_000

    /** Stored chat memory size: number of recent messages sent to the model, 0 = whole chat. */
    fun contextMessages(context: Context): Int =
        prefs(context).getInt(KEY_CONTEXT_MESSAGES, NORMAL_CONTEXT_MESSAGES).let { if (it <= 0) 0 else it.coerceIn(MEMORY_MIN, MEMORY_MAX) }

    fun setContextMessages(context: Context, v: Int) {
        prefs(context).edit().putInt(KEY_CONTEXT_MESSAGES, if (v <= 0) 0 else v.coerceIn(MEMORY_MIN, MEMORY_MAX)).apply()
    }

    fun contextMessageLimit(context: Context): Int {
        val base = contextMessages(context).let { if (it == 0) WHOLE_CHAT else it }
        return if (limitContext(context)) minOf(base, SHORT_CONTEXT_MESSAGES) else base
    }

    fun trimLongMessages(context: Context): Boolean = prefs(context).getBoolean(KEY_TRIM_LONG, false)
    fun setTrimLongMessages(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_TRIM_LONG, v).apply() }

    const val OLD_CHARS_MIN = 500
    const val OLD_CHARS_MAX = 6000
    const val DEFAULT_OLD_CHARS = 1500

    /** Old messages longer than this are shortened when "trim long messages" is on. */
    fun oldMessageMaxChars(context: Context): Int =
        prefs(context).getInt(KEY_OLD_MAX_CHARS, DEFAULT_OLD_CHARS).coerceIn(OLD_CHARS_MIN, OLD_CHARS_MAX)

    fun setOldMessageMaxChars(context: Context, v: Int) {
        prefs(context).edit().putInt(KEY_OLD_MAX_CHARS, v.coerceIn(OLD_CHARS_MIN, OLD_CHARS_MAX)).apply()
    }

    const val FRESH_MIN = 1
    const val FRESH_MAX = 12
    const val DEFAULT_FRESH = 4

    /** How many of the newest messages are always sent as they are (no trimming, no code stripping). */
    fun freshMessages(context: Context): Int =
        prefs(context).getInt(KEY_FRESH_MESSAGES, DEFAULT_FRESH).coerceIn(FRESH_MIN, FRESH_MAX)

    fun setFreshMessages(context: Context, v: Int) {
        prefs(context).edit().putInt(KEY_FRESH_MESSAGES, v.coerceIn(FRESH_MIN, FRESH_MAX)).apply()
    }

    fun keepFirstMessage(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_FIRST, false)
    fun setKeepFirstMessage(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_KEEP_FIRST, v).apply() }

    private const val PINNED_MAX_CHARS = 600

    /**
     * First user message of the chat, passed through the system prompt once the context window
     * no longer reaches it. Null when the option is off or the whole chat still fits.
     */
    fun pinnedStart(context: Context, history: JSONArray): String? {
        if (!keepFirstMessage(context) || history.length() <= contextMessageLimit(context)) return null
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optString("role") != "user") continue
            val text = messageText(m).replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            return if (text.length > PINNED_MAX_CHARS) text.take(PINNED_MAX_CHARS) + "…" else text
        }
        return null
    }

    private fun messageText(m: org.json.JSONObject): String {
        val c = m.opt("content")
        if (c is String) return c
        if (c !is JSONArray) return ""
        val sb = StringBuilder()
        for (j in 0 until c.length()) {
            val p = c.optJSONObject(j) ?: continue
            if (p.optString("type") == "text") sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    /** Rough size of what the next request carries from history (about 4 chars per token). */
    fun estimateContextTokens(context: Context, history: JSONArray): Int {
        val from = maxOf(0, history.length() - contextMessageLimit(context))
        var chars = 0
        for (i in from until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            var len = messageText(m).length
            val old = i < history.length() - freshMessages(context)
            if (old && trimLongMessages(context)) len = minOf(len, oldMessageMaxChars(context))
            chars += len
        }
        return chars / 4
    }

    /** Keeps the beginning and the end of an old long message and drops the middle. */
    fun shortenLongText(text: String, maxChars: Int = DEFAULT_OLD_CHARS): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars * 3 / 5) + "\n[… сокращено для экономии токенов …]\n" + text.takeLast(maxChars * 4 / 15)
    }

    fun conciseAnswers(context: Context): Boolean = prefs(context).getBoolean(KEY_CONCISE, false)
    fun setConciseAnswers(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_CONCISE, v).apply() }

    fun slowModelSync(context: Context): Boolean = prefs(context).getBoolean(KEY_SLOW_SYNC, false)
    fun setSlowModelSync(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_SLOW_SYNC, v).apply() }

    fun gifSingleFrame(context: Context): Boolean = prefs(context).getBoolean(KEY_GIF_SINGLE_FRAME, false)
    fun setGifSingleFrame(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_GIF_SINGLE_FRAME, v).apply() }

    fun disablePersonaPrompt(context: Context): Boolean = prefs(context).getBoolean(KEY_DISABLE_PERSONA, false)
    fun setDisablePersonaPrompt(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_PERSONA, v).commit() }

    fun disableSystemPrompt(context: Context): Boolean = prefs(context).getBoolean(KEY_DISABLE_SYSTEM_PROMPT, false)
    fun setDisableSystemPrompt(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_SYSTEM_PROMPT, v).commit() }

    fun disableMemoryPrompt(context: Context): Boolean = prefs(context).getBoolean(KEY_DISABLE_MEMORY, false)
    fun setDisableMemoryPrompt(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_MEMORY, v).commit() }

    fun disableAutoRetry(context: Context): Boolean = prefs(context).getBoolean(KEY_DISABLE_AUTO_RETRY, false)
    fun setDisableAutoRetry(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_AUTO_RETRY, v).apply() }

    fun typingAnimationEnabled(context: Context): Boolean = UiPrefs.animationsEnabled(context)
    fun setTypingAnimationEnabled(context: Context, v: Boolean) { UiPrefs.setAnimationsEnabled(context, v) }

    fun pressAnimationsEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_PRESS_ANIM, false)
    fun setPressAnimationsEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_PRESS_ANIM, !v).apply() }

    fun globalTokenLimitEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_GLOBAL_TOKEN_LIMIT_ENABLED, false)
    fun setGlobalTokenLimitEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_GLOBAL_TOKEN_LIMIT_ENABLED, v).apply() }

    fun globalTokenLimitValue(context: Context): Int = prefs(context).getInt(KEY_GLOBAL_TOKEN_LIMIT_VALUE, DEFAULT_GLOBAL_TOKEN_LIMIT)
    fun setGlobalTokenLimitValue(context: Context, v: Int) { prefs(context).edit().putInt(KEY_GLOBAL_TOKEN_LIMIT_VALUE, v.coerceAtLeast(1)).apply() }

    fun resolveMaxTokens(context: Context, baseMaxTokens: Int, modelTokenLimit: Int): Int = when {
        modelTokenLimit > 0 -> modelTokenLimit
        globalTokenLimitEnabled(context) -> globalTokenLimitValue(context)
        else -> baseMaxTokens
    }

    fun suggestionsEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_SUGGESTIONS, false)
    fun setSuggestionsEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_SUGGESTIONS, !v).apply() }

    fun autoTitleEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_AUTO_TITLE, false)
    fun setAutoTitleEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_AUTO_TITLE, !v).apply() }

    fun imagePromptBoostEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_IMAGE_BOOST, false)
    fun setImagePromptBoostEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_IMAGE_BOOST, !v).apply() }

    const val SHORT_SEARCH_RESULTS = 3
    fun shortSearch(context: Context): Boolean = prefs(context).getBoolean(KEY_SHORT_SEARCH, false)
    fun setShortSearch(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_SHORT_SEARCH, v).apply() }

    fun blurEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_BLUR, false)
    fun setBlurEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_BLUR, !v).apply() }

    fun glowEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_GLOW, false)
    fun setGlowEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_GLOW, !v).apply() }

    fun transitionsEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_TRANSITIONS, false)
    fun setTransitionsEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_TRANSITIONS, !v).apply() }

    fun syntaxHighlightEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_SYNTAX, false)
    fun setSyntaxHighlightEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_SYNTAX, !v).apply() }

    fun lightImages(context: Context): Boolean = prefs(context).getBoolean(KEY_LIGHT_IMAGES, false)
    fun setLightImages(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_LIGHT_IMAGES, v).apply() }
    fun imageMaxSide(context: Context): Int = if (lightImages(context)) 640 else 1024
    fun imageJpegQuality(context: Context): Int = if (lightImages(context)) 60 else 80
    fun agentShotWidth(context: Context): Int = if (lightImages(context)) 512 else 720
    fun agentShotQuality(context: Context): Int = if (lightImages(context)) 45 else 65

    fun oneSearchRound(context: Context): Boolean = prefs(context).getBoolean(KEY_ONE_SEARCH_ROUND, false)
    fun setOneSearchRound(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_ONE_SEARCH_ROUND, v).apply() }
    fun maxToolRounds(context: Context): Int = if (oneSearchRound(context)) 1 else 3

    fun capReasoning(context: Context): Boolean = prefs(context).getBoolean(KEY_CAP_REASONING, false)
    fun setCapReasoning(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_CAP_REASONING, v).apply() }

    const val AGENT_SHORT_STEPS = 12
    fun agentShortRun(context: Context): Boolean = prefs(context).getBoolean(KEY_AGENT_SHORT_RUN, false)
    fun setAgentShortRun(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_AGENT_SHORT_RUN, v).apply() }
    fun agentMaxSteps(context: Context): Int = if (agentShortRun(context)) AGENT_SHORT_STEPS else AgentProtocol.MAX_STEPS

    fun agentShortPage(context: Context): Boolean = prefs(context).getBoolean(KEY_AGENT_SHORT_PAGE, false)
    fun setAgentShortPage(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_AGENT_SHORT_PAGE, v).apply() }
    fun agentPageTextChars(context: Context): Int = if (agentShortPage(context)) 1200 else 3000
    fun agentMaxElements(context: Context): Int = if (agentShortPage(context)) 40 else 70

    fun agentTextOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_AGENT_TEXT_ONLY, false)
    fun setAgentTextOnly(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_AGENT_TEXT_ONLY, v).apply() }

    fun stripOldCode(context: Context): Boolean = prefs(context).getBoolean(KEY_STRIP_OLD_CODE, false)
    fun setStripOldCode(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_STRIP_OLD_CODE, v).apply() }

    private val CODE_FENCE = Regex("(?s)```([^\\n`]*)\\n(.*?)```")
    private const val OLD_CODE_MIN_CHARS = 600

    fun compactCodeBlocks(text: String): String {
        if (!text.contains("```")) return text
        return CODE_FENCE.replace(text) { m ->
            val body = m.groupValues[2]
            if (body.length < OLD_CODE_MIN_CHARS) m.value
            else "```" + m.groupValues[1] + "\n[... код скрыт для экономии токенов: " + body.lines().size + " строк ...]\n```"
        }
    }

    fun throttleStreaming(context: Context): Boolean = prefs(context).getBoolean(KEY_THROTTLE_STREAM, false)
    fun setThrottleStreaming(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_THROTTLE_STREAM, v).apply() }

    fun pulseEnabled(context: Context): Boolean = !prefs(context).getBoolean(KEY_DISABLE_PULSE, false)
    fun setPulseEnabled(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DISABLE_PULSE, !v).apply() }

    fun economyModeEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ECONOMY_MODE, false)

    fun setEconomyMode(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_ECONOMY_MODE, v).apply()
        setLimitContext(context, v)
        setGifSingleFrame(context, v)
        setDisableAutoRetry(context, v)
        setTypingAnimationEnabled(context, !v)
        setPressAnimationsEnabled(context, !v)
        setSuggestionsEnabled(context, !v)
        setAutoTitleEnabled(context, !v)
        setShortSearch(context, v)
        setBlurEnabled(context, !v)
        setGlowEnabled(context, !v)
        setTransitionsEnabled(context, !v)
        setLightImages(context, v)
        setOneSearchRound(context, v)
        setCapReasoning(context, v)
        setAgentShortRun(context, v)
        setAgentShortPage(context, v)
        setThrottleStreaming(context, v)
        setStripOldCode(context, v)
        setPulseEnabled(context, !v)
        setTrimLongMessages(context, v)
        setConciseAnswers(context, v)
        setSlowModelSync(context, v)
    }
}
