package com.youraicqa.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppErrorUiTest {
    @Test
    fun `catalogue assigns stable codes to representative failures`() {
        val cases = listOf(
            "NO_API_KEY" to 22,
            "HTTP 401: invalid API key" to 1,
            "empty response from model" to 3,
            "Model not found" to 4,
            "Connection timed out" to 5,
            "[[SRVERR]]Не удаётся подключиться к серверу.\nКод ошибки: E06" to 6,
            "Plugin runtime failed" to 8,
            "HTTP 429 rate limit exceeded" to 9,
            "This model does not support image input" to 10,
            "System prompt could not be parsed" to 11,
            "Corrupted PDF document" to 12,
            "SEARCH_ERROR: no route to host" to 15,
            "Incorrect account password" to 17,
            "GGUF local model failed to load" to 19,
            "maximum context length exceeded" to 20,
            "Ответ оборван: соединение с сервером прервалось.\nКод ошибки: E21" to 21
        )
        cases.forEach { (message, expectedCode) ->
            assertEquals(message, expectedCode, AppErrorUi.classify(message).code)
        }
    }

    @Test
    fun `generic local failures are classified as local model errors`() {
        assertEquals(19, AppErrorUi.classify("unknown native failure", isLocal = true).code)
    }
}
