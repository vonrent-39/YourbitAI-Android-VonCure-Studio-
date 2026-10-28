package com.youraicqa.app

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class ServerError(val code: String?, val message: String, val raw: String = "") {
    fun display(): String = if (code == null) message else "$message\nКод ошибки: $code"
}

class ServerException(val error: ServerError, cause: Throwable? = null) : Exception(error.display(), cause)

class StreamCutException(val partialText: String, message: String = "Поток ответа оборван") : Exception(message)

class CertChangedException(val expected: String, val actual: String?) :
    java.security.cert.CertificateException("Отпечаток сертификата не совпал с закреплённым")

object ServerErrors {
    const val E01 = "E01"
    const val E03 = "E03"
    const val E05 = "E05"
    const val E06 = "E06"
    const val E09 = "E09"
    const val E14 = "E14"
    const val E21 = "E21"
    const val E23 = "E23"

    const val RAW_LIMIT = 300

    fun noNetwork() = ServerError(E14, "Нет подключения к интернету.")
    fun cannotConnect() = ServerError(E06, "Не удаётся подключиться к серверу. Проверьте адрес и доступность сервера.")
    fun timeout() = ServerError(E05, "Сервер не ответил вовремя.")
    fun unauthorized() = ServerError(E01, "Сервер не принял ключ доступа. Проверьте ключ.")
    fun notFound() = ServerError(E03, "Сервер не нашёл адрес или модель. Проверьте адрес и название модели.")
    fun rateLimited() = ServerError(E09, "Сервер ограничил число запросов. Подождите немного и повторите.")
    fun streamCut() = ServerError(E21, "Ответ оборван: соединение с сервером прервалось.")
    fun contentFiltered() = ServerError(E23, "Сервер отклонил запрос из-за фильтра содержимого.")
    fun certChanged() = ServerError(
        null,
        "Сертификат сервера изменился. Откройте настройки сервера и нажмите «Проверить», чтобы увидеть и подтвердить новый отпечаток."
    )
    fun certNotPinned() = ServerError(
        null,
        "Для сервера включено доверие самоподписанному сертификату, но отпечаток ещё не подтверждён. Откройте настройки сервера и нажмите «Проверить»."
    )
    fun tlsFailed() = ServerError(
        null,
        "Не удалось установить защищённое соединение: сертификат сервера не принят системой. " +
            "Если сертификат самоподписанный — включите в настройках сервера доверие самоподписанному сертификату и нажмите «Проверить»."
    )
    fun emptyReply() = ServerError(null, "Сервер вернул пустой ответ.")
    fun emptyReply(detail: String) =
        if (detail.isBlank()) emptyReply() else ServerError(null, "Сервер вернул пустой ответ ($detail).", detail)
    fun badReply(body: String? = null): ServerError {
        val snippet = body?.replace(Regex("\\s+"), " ")?.trim()?.take(160).orEmpty()
        return ServerError(
            null,
            "Сервер вернул ответ в неожиданном формате." +
                (if (snippet.isEmpty()) "" else "\nНачало ответа: $snippet")
        )
    }

    private val CONTENT_FILTER_HINTS = listOf(
        "content_filter", "content filter", "content_policy", "content policy",
        "contentfilter", "responsibleai", "moderation", "safety", "blocked by", "flagged"
    )
    private val RATE_HINTS = listOf("rate limit", "rate_limit", "ratelimit", "too many requests", "quota")

    fun looksLikeContentFilter(body: String): Boolean {
        val b = body.lowercase()
        return CONTENT_FILTER_HINTS.any { b.contains(it) }
    }

    private fun looksLikeModelNotFound(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("model") && (
            b.contains("not found") || b.contains("does not exist") || b.contains("not exist") ||
                b.contains("unknown model") || b.contains("no such model") || b.contains("not_found")
            )
    }

    private fun looksLikeRateLimit(body: String): Boolean {
        val b = body.lowercase()
        return RATE_HINTS.any { b.contains(it) }
    }

    fun fromHttp(status: Int, body: String): ServerError =
        classifyHttp(status, body).copy(raw = body.trim().take(500))

    private fun classifyHttp(status: Int, body: String): ServerError = when {
        status == 401 || status == 403 -> unauthorized()
        status == 429 -> rateLimited()
        status == 400 && looksLikeContentFilter(body) -> contentFiltered()
        status == 404 -> notFound()
        (status == 400 || status == 422) && looksLikeModelNotFound(body) -> notFound()
        looksLikeRateLimit(body) -> rateLimited()
        else -> ServerError(null, "HTTP $status: ${body.trim().take(RAW_LIMIT)}".trim())
    }

    fun fromThrowable(e: Throwable, online: Boolean? = null): ServerError {
        var cur: Throwable? = e
        var depth = 0
        while (cur != null && depth < 8) {
            when (cur) {
                is ServerException -> return cur.error
                is StreamCutException -> return streamCut()
                is CertChangedException -> return certChanged()
            }
            cur = cur.cause
            depth++
        }
        return when (e) {
            is SSLException -> tlsFailed()
            is UnknownHostException, is ConnectException ->
                if (online == false) noNetwork() else cannotConnect()
            is SocketTimeoutException -> if (online == false) noNetwork() else timeout()
            is IOException -> if (online == false) noNetwork() else cannotConnect()
            else -> ServerError(null, (e.message ?: "Неизвестная ошибка").trim().take(RAW_LIMIT))
        }
    }
}
