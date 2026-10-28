package com.youraicqa.app.local

object ReplySanitizer {
    fun stripLeakedSystemPrompt(reply: String, systemPromptText: String): String {
        if (reply.isBlank()) return reply

        var result = reply

        val roleCut = Regex(
            "(?is)(?:<\\|im_start\\|>\\s*(?:user|assistant|system)|" +
                "<\\|start_header_id\\|>\\s*(?:user|assistant|system)|" +
                "<\\|(?:user|assistant|system)\\|>|<start_of_turn>|" +
                "^\\s*(?:Пользователь|User|Human)\\s*:)",
            setOf(RegexOption.MULTILINE)
        )
        result = result.replace(Regex("^\\s*(?:<\\|assistant\\|>|<start_of_turn>\\s*model)\\s*"), "")
        roleCut.find(result)?.let { m ->
            if (m.range.first > 0) result = result.substring(0, m.range.first)
        }
        Regex("<\\|end\\|>|<end_of_turn>").find(result)?.let { m ->
            result = result.substring(0, m.range.first)
        }

        result = result
            .replace(Regex("<\\|im_end\\|>"), "")
            .replace(Regex("<\\|eot_id\\|>"), "")
            .replace(Regex("<\\|end_of_text\\|>"), "")
            .replace(Regex("<\\|end_header_id\\|>"), "")
            .replace(Regex("<\\|begin_of_text\\|>"), "")
            .replace(Regex("<\\|im_start\\|>\\s*assistant\\s*"), "")
            .replace(Regex("<\\|start_header_id\\|>\\s*assistant\\s*<\\|end_header_id\\|>\\s*"), "")

        if (systemPromptText.isNotBlank()) {
            fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ")

            val chunks = linkedSetOf<String>()
            for (line in systemPromptText.split("\n")) {
                val l = normalize(line)
                if (l.length >= 24) chunks.add(l)
                for (sentence in l.split(Regex("(?<=[.!?])\\s+"))) {
                    val s = normalize(sentence)
                    if (s.length >= 24) chunks.add(s)
                }
            }
            for (chunk in chunks.sortedByDescending { it.length }) {
                val pattern = Regex(Regex.escape(chunk).replace(" ", "\\s+"), RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(result)) result = pattern.replace(result, " ")
            }
        }

        return result.replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex("(?m)^\\s+$"), "")
            .trim()
    }
}
