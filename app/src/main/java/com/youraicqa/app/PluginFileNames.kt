package com.youraicqa.app

object PluginFileNames {
    private const val MAX_LEN = 180

    fun sanitize(raw: String): String? {
        val stripped = raw.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001F]"""), "")
            .trim()
        if (stripped.isEmpty()) return null
        if (stripped == "." || stripped == "..") return null
        if (stripped.startsWith("..")) return null
        val cleaned = stripped.replace(Regex("""[<>:"|?*\\/]"""), "_")
        if (cleaned.isBlank()) return null
        return cleaned.take(MAX_LEN)
    }
}
