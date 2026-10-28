package com.youraicqa.app

import java.security.MessageDigest

object PluginScanner {

    data class ScanResult(
        val flagged: Boolean,
        val reasons: List<String>,
        val sha256: String
    )

    private val SUSPICIOUS_PATTERNS = listOf(
        Regex("""\bJava\.(perform|use|available)\b"""),
        Regex("""\bandroid\.app\.[A-Za-z]+\b"""),
        Regex("""\bClass\.forName\s*\("""),
        Regex("""\bnew\s+Function\s*\("""),
        Regex("""\beval\s*\("""),
        Regex("""\bdocument\.write\s*\("""),
        Regex("""(?i)\bsharedpreferences\b"""),
        Regex("""window\s*\[\s*["']AndroidBridge"""),
    )

    private const val MAX_SCRIPT_BYTES = 200_000
    private const val OBFUSCATION_RATIO_LIMIT = 0.35

    fun scan(manifestText: String, script: String?): ScanResult {
        val reasons = mutableListOf<String>()
        val digest = sha256(manifestText + (script ?: ""))

        if (manifestText.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES ||
            (script?.toByteArray(Charsets.UTF_8)?.size ?: 0) > MAX_SCRIPT_BYTES
        ) {
            reasons += "Файл превышает допустимый размер для .plugin"
        }

        val code = script.orEmpty()
        if (code.isNotEmpty()) {
            for (pattern in SUSPICIOUS_PATTERNS) {
                if (pattern.containsMatchIn(code)) {
                    reasons += "Подозрительная конструкция в скрипте: ${pattern.pattern}"
                }
            }
            val escapeCount = Regex("""\\x[0-9a-fA-F]{2}|\\u[0-9a-fA-F]{4}""").findAll(code).count()
            val ratio = if (code.isNotEmpty()) escapeCount.toDouble() / code.length else 0.0
            if (ratio > OBFUSCATION_RATIO_LIMIT) {
                reasons += Loc.t("Похоже на обфусцированный/упакованный код (доля escape-последовательностей ${"%.0f".format(ratio * 100)}%)")
            }
            val base64Blob = Regex("""["'][A-Za-z0-9+/]{400,}={0,2}["']""")
            if (base64Blob.containsMatchIn(code)) {
                reasons += "Обнаружен крупный base64-блок — плагин должен быть читаемым JS, а не упакованным бинарником"
            }
        }

        val remote = remoteReputationCheck(digest)
        if (remote != null && remote.malicious) {
            reasons += "Файл с таким хэшем помечен как вредоносный внешней базой сигнатур"
        }

        return ScanResult(flagged = reasons.isNotEmpty(), reasons = reasons, sha256 = digest)
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class RemoteVerdict(val malicious: Boolean)

    private fun remoteReputationCheck(@Suppress("UNUSED_PARAMETER") sha256: String): RemoteVerdict? = null
}
