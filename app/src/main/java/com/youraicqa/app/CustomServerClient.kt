package com.youraicqa.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

object CustomServerClient {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val FIRST_BYTE_TIMEOUT_MS = 90_000
    const val STREAM_IDLE_TIMEOUT_MS = 60_000L
    const val BACKGROUND_TIMEOUT_MS = 8_000

    val addressMemory = ServerLogic.AddressMemory()

    fun isOnline(context: Context): Boolean? = try {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) { null }

    suspend fun listModelsInfo(
        server: CustomServer,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = FIRST_BYTE_TIMEOUT_MS,
        isOnline: () -> Boolean? = { null }
    ): ServerLogic.ParsedModels = blocking { handle ->
        withFailover(server) { base ->
            listAt(base, server, connectTimeoutMs, readTimeoutMs, handle, isOnline)
        }
    }

    suspend fun listModels(
        server: CustomServer,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = FIRST_BYTE_TIMEOUT_MS,
        isOnline: () -> Boolean? = { null }
    ): List<String> = listModelsInfo(server, connectTimeoutMs, readTimeoutMs, isOnline).ids

    suspend fun listModelsAt(
        baseUrl: String,
        server: CustomServer,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = FIRST_BYTE_TIMEOUT_MS,
        isOnline: () -> Boolean? = { null }
    ): ServerLogic.ParsedModels = blocking { handle ->
        try {
            listAt(baseUrl, server, connectTimeoutMs, readTimeoutMs, handle, isOnline)
        } catch (f: AttemptFailure) {
            throw ServerException(f.error, f.cause)
        }
    }

    suspend fun chat(
        server: CustomServer,
        model: String,
        messages: JSONArray,
        maxTokens: Int,
        onDelta: (String) -> Unit = {},
        isOnline: () -> Boolean? = { null },
        opts: ChatOpts? = null
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", maxTokens)
            .put("temperature", 0.7)
            .put("stream", server.streaming)
            .toString()
        return blocking { handle ->
            withFailover(server) { base -> chatAt(base, server, body, onDelta, handle, isOnline, opts) }
        }
    }

    data class CertInfo(val fingerprintHex: String, val subject: String, val notAfterMs: Long)

    suspend fun fetchCertificate(
        baseUrl: String,
        timeoutMs: Int = CONNECT_TIMEOUT_MS,
        isOnline: () -> Boolean? = { null }
    ): CertInfo = blocking { handle ->
        val u = try { URL(baseUrl) } catch (_: Exception) {
            throw ServerException(ServerError(null, "Некорректный адрес сервера."))
        }
        val port = if (u.port > 0) u.port else 443
        val box = arrayOfNulls<X509Certificate>(1)
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
                throw CertificateException("client certificates are not supported")
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                box[0] = chain?.firstOrNull()
                throw CertificateException("certificate captured, handshake rejected on purpose")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        val plain = Socket()
        var ssl: SSLSocket? = null
        try {
            plain.connect(InetSocketAddress(u.host, port), timeoutMs)
            ssl = ctx.socketFactory.createSocket(plain, u.host, port, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            try { ssl.startHandshake() } catch (_: SSLException) {  }
        } catch (e: Exception) {
            if (handle.cancelled) throw CancellationException("cancelled")
            if (box[0] == null) throw ServerException(ServerErrors.fromThrowable(e, isOnline()), e)
        } finally {
            try { ssl?.close() } catch (_: Exception) {}
            try { plain.close() } catch (_: Exception) {}
        }
        val cert = box[0] ?: throw ServerException(ServerErrors.cannotConnect())
        CertInfo(
            fingerprintHex = ServerLogic.sha256Hex(cert.encoded),
            subject = cert.subjectX500Principal?.name.orEmpty(),
            notAfterMs = cert.notAfter?.time ?: 0L
        )
    }

    private class AttemptFailure(val error: ServerError, val failover: Boolean, cause: Throwable? = null) :
        Exception(error.display(), cause)

    private class CallHandle {
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var cancelled = false
        fun cancel() { cancelled = true; try { conn?.disconnect() } catch (_: Exception) {} }
    }

    private suspend fun <T> blocking(block: (CallHandle) -> T): T = withContext(Dispatchers.IO) {
        val handle = CallHandle()
        coroutineScope {
            val watcher = launch { try { awaitCancellation() } finally { handle.cancel() } }
            try {
                block(handle)
            } catch (e: AttemptFailure) {
                throw ServerException(e.error, e.cause)
            } finally {
                watcher.cancel()
            }
        }
    }

    private fun <T> withFailover(server: CustomServer, attempt: (String) -> T): T {
        val order = ServerLogic.orderAddresses(server.baseUrl, server.fallbackUrl, addressMemory.get(server.id))
        var last: AttemptFailure? = null
        for ((i, base) in order.withIndex()) {
            try {
                val result = attempt(base)
                if (i > 0) addressMemory.remember(server.id, base)
                return result
            } catch (f: AttemptFailure) {
                last = f
                if (!f.failover || i == order.lastIndex) throw f
            }
        }
        throw last ?: AttemptFailure(ServerErrors.cannotConnect(), false)
    }

    private fun open(
        url: String, server: CustomServer, method: String, connectTimeoutMs: Int, readTimeoutMs: Int,
        handle: CallHandle, accept: String
    ): HttpURLConnection {
        val opened: HttpsURLConnection? = try {
            URL(url).openConnection() as? HttpsURLConnection
        } catch (_: Exception) { null }
        val c = opened ?: throw AttemptFailure(ServerError(null, "Адрес сервера должен начинаться с https://."), false)
        if (server.trustSelfSigned && ServerLogic.sameHost(url, server.baseUrl)) {
            val pin = server.pinnedCertSha256
            if (pin.isBlank()) throw AttemptFailure(ServerErrors.certNotPinned(), false)
            c.sslSocketFactory = SSLContext.getInstance("TLS")
                .apply { init(null, arrayOf(PinnedTrustManager(pin)), null) }.socketFactory
        }
        c.instanceFollowRedirects = false
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("Accept", accept)
        if (accept.contains("event-stream")) c.setRequestProperty("Accept-Encoding", "identity")
        if (server.apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${server.apiKey}")
        handle.conn = c
        return c
    }

    private fun classify(phase: ServerLogic.Phase, e: Exception, handle: CallHandle, isOnline: () -> Boolean?): Exception {
        if (handle.cancelled) return CancellationException("cancelled")
        if (e is AttemptFailure || e is CancellationException || e is StreamCutException || e is ServerException) return e
        val error = ServerErrors.fromThrowable(e, isOnline())
        return AttemptFailure(error, ServerLogic.shouldFailover(phase, e), e)
    }

    private fun failFromStatus(c: HttpURLConnection, code: Int): AttemptFailure {
        if (code in 300..399) {
            return AttemptFailure(
                ServerError(null, "Сервер перенаправляет запрос на другой адрес (HTTP $code). Укажите итоговый адрес сервера в настройках."),
                false
            )
        }
        val text = try {
            c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { r ->
                val buf = CharArray(2048); val n = r.read(buf); if (n > 0) String(buf, 0, n) else ""
            }.orEmpty()
        } catch (_: Exception) { "" }
        return AttemptFailure(ServerErrors.fromHttp(code, text), ServerLogic.shouldFailoverOnStatus(code))
    }

    private fun listAt(
        base: String, server: CustomServer, connectTimeoutMs: Int, readTimeoutMs: Int,
        handle: CallHandle, isOnline: () -> Boolean?
    ): ServerLogic.ParsedModels {
        var c: HttpURLConnection? = null
        try {
            c = open("$base/models", server, "GET", connectTimeoutMs, readTimeoutMs, handle, "application/json")
            try { c.connect() } catch (e: Exception) { throw classify(ServerLogic.Phase.CONNECT, e, handle, isOnline) }
            try {
                val code = c.responseCode
                if (code !in 200..299) throw failFromStatus(c, code)
                val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return ServerLogic.parseModels(text)
            } catch (e: Exception) {
                throw classify(ServerLogic.Phase.RESPONSE, e, handle, isOnline)
            }
        } finally {
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun chatAt(
        base: String, server: CustomServer, body: String, onDelta: (String) -> Unit,
        handle: CallHandle, isOnline: () -> Boolean?, opts: ChatOpts? = null
    ): String {
        var c: HttpURLConnection? = null
        val acc = StringBuilder()
        var gotData = false
        val watchdog = IdleWatchdog(STREAM_IDLE_TIMEOUT_MS) { handle.conn?.disconnect() }
        try {
            c = open(
                "$base/chat/completions", server, "POST", CONNECT_TIMEOUT_MS, FIRST_BYTE_TIMEOUT_MS, handle,
                if (server.streaming) "text/event-stream, application/json" else "application/json"
            )
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            try { c.connect() } catch (e: Exception) { throw classify(ServerLogic.Phase.CONNECT, e, handle, isOnline) }
            try {
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) throw failFromStatus(c, code)
                val reader = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8))
                val lines = sequence {
                    while (true) {
                        val line = reader.readLine() ?: break
                        gotData = true
                        watchdog.touch()
                        yield(line)
                    }
                }
                return ServerLogic.readChatResponse(c.contentType, lines, opts) { piece ->
                    acc.append(piece)
                    onDelta(piece)
                }
            } catch (e: Exception) {
                if (handle.cancelled) throw CancellationException("cancelled")
                if (e is IOException && (gotData || watchdog.fired)) throw StreamCutException(acc.toString())
                throw classify(ServerLogic.Phase.RESPONSE, e, handle, isOnline)
            }
        } finally {
            watchdog.stop()
            try { c?.disconnect() } catch (_: Exception) {}
        }
    }

    private class IdleWatchdog(private val limitMs: Long, private val onStall: () -> Unit) {
        private val last = AtomicLong(0L)
        @Volatile private var armed = false
        @Volatile private var stopped = false
        @Volatile var fired = false
            private set
        private var thread: Thread? = null

        fun touch() {
            last.set(System.nanoTime())
            if (!armed) {
                armed = true
                thread = Thread {
                    try {
                        while (!stopped) {
                            Thread.sleep(1000)
                            if (stopped) break
                            if ((System.nanoTime() - last.get()) / 1_000_000L > limitMs) {
                                fired = true
                                onStall()
                                break
                            }
                        }
                    } catch (_: InterruptedException) {}
                }.apply { isDaemon = true; name = "server-idle-watchdog"; start() }
            }
        }

        fun stop() { stopped = true; thread?.interrupt() }
    }

    private class PinnedTrustManager(pin: String) : X509TrustManager {
        private val expected = ServerLogic.normalizeFingerprint(pin)
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not supported")
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val actual = ServerLogic.sha256Hex(leaf.encoded)
            if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) {
                throw CertChangedException(expected, actual)
            }
            leaf.checkValidity()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
