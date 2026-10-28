package com.youraicqa.app.local

import org.json.JSONArray

object LocalPromptFormatter {
    fun build(format: String, systemPrompt: String, messages: JSONArray): String = when (format) {
        "llama3" -> buildLlama3(systemPrompt, messages)
        "phi" -> buildPhi(systemPrompt, messages)
        "gemma" -> buildGemma(systemPrompt, messages)
        "plain" -> buildPlain(systemPrompt, messages)
        else -> buildChatMl(systemPrompt, messages)
    }

    fun resolveFormat(format: String, modelName: String, uri: String): String {
        if (format.isNotBlank() && format != "chatml") return format
        val hint = (modelName + " " + uri.substringAfterLast('/')).lowercase()
        return when {
            Regex("(^|[^a-z])phi[-_ .]?\\d").containsMatchIn(hint) -> "phi"
            hint.contains("gemma") -> "gemma"
            Regex("llama[-_ .]?3").containsMatchIn(hint) -> "llama3"
            else -> "chatml"
        }
    }

    private fun buildPhi(systemPrompt: String, messages: JSONArray): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("<|system|>\n").append(systemPrompt).append("<|end|>\n")
        }
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") continue
            val tag = if (role == "assistant") "assistant" else "user"
            sb.append("<|").append(tag).append("|>\n").append(m.optString("content")).append("<|end|>\n")
        }
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    private fun buildGemma(systemPrompt: String, messages: JSONArray): String {
        val sb = StringBuilder()
        var systemPending = systemPrompt.isNotBlank()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") continue
            val tag = if (role == "assistant") "model" else "user"
            sb.append("<start_of_turn>").append(tag).append("\n")
            if (tag == "user" && systemPending) {
                sb.append(systemPrompt).append("\n\n")
                systemPending = false
            }
            sb.append(m.optString("content")).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildChatMl(systemPrompt: String, messages: JSONArray): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            sb.append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
        }
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") continue
            sb.append("<|im_start|>").append(role).append("\n")
                .append(m.optString("content")).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildLlama3(systemPrompt: String, messages: JSONArray): String {
        val sb = StringBuilder("<|begin_of_text|>")
        if (systemPrompt.isNotBlank()) {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
                .append(systemPrompt).append("<|eot_id|>")
        }
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") continue
            sb.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
                .append(m.optString("content")).append("<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    private fun buildPlain(systemPrompt: String, messages: JSONArray): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) sb.append(systemPrompt).append("\n\n")
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val role = m.optString("role")
            if (role == "system") continue
            val label = if (role == "user") "Пользователь" else "Ассистент"
            sb.append(label).append(": ").append(m.optString("content")).append("\n")
        }
        sb.append("Ассистент:")
        return sb.toString()
    }
}
