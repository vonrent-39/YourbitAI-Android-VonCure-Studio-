package com.youraicqa.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.json.JSONObject

data class PluginHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: InputStream,
    val close: () -> Unit
)

interface PluginHttpConnector {
    fun open(url: URL, method: String, headers: Map<String, String>, timeoutMs: Int, body: ByteArray?): PluginHttpResponse
    fun resolve(host: String): List<InetAddress>
}

class DefaultPluginHttpConnector : PluginHttpConnector {
    override fun resolve(host: String): List<InetAddress> =
        try { InetAddress.getAllByName(host).toList() } catch (_: Exception) { emptyList() }

    override fun open(url: URL, method: String, headers: Map<String, String>, timeoutMs: Int, body: ByteArray?): PluginHttpResponse {
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
        }
        val status = conn.responseCode
        val stream = try { conn.inputStream } catch (_: Exception) { conn.errorStream }
        return PluginHttpResponse(
            status = status,
            headers = conn.headerFields.filterKeys { it != null }.mapKeys { it.key!! },
            body = stream ?: ByteArrayInputStream(ByteArray(0)),
            close = { conn.disconnect() }
        )
    }
}

sealed class PluginNetResult {
    data class Success(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray, val truncated: Boolean) : PluginNetResult()
    data class Error(val reason: String) : PluginNetResult()
}

object PluginNetCapability {
    const val DEFAULT_TIMEOUT_MS = 15_000
    const val MAX_TIMEOUT_MS = 60_000
    const val MAX_BODY_BYTES = 10 * 1024 * 1024

    fun validateHop(url: URL, allowlist: Set<String>, connector: PluginHttpConnector): String? {
        if (!PluginNetGuard.isHostAllowed(url.host, allowlist)) return "domain_not_allowed"
        val addresses = connector.resolve(url.host)
        if (addresses.isEmpty()) return "dns_resolution_failed"
        if (addresses.any { PluginNetGuard.isBlockedAddress(it) }) return "blocked_address"
        return null
    }

    fun request(
        connector: PluginHttpConnector,
        allowlist: Set<String>,
        rawUrl: String,
        method: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        requestBody: ByteArray?
    ): PluginNetResult {
        return when (val opened = openFollowingRedirects(connector, allowlist, rawUrl, method, headers, timeoutMs, requestBody)) {
            is OpenResult.Error -> PluginNetResult.Error(opened.reason)
            is OpenResult.Opened -> try {
                val (bytes, truncated) = readCapped(opened.response.body, MAX_BODY_BYTES)
                PluginNetResult.Success(opened.response.status, opened.response.headers, bytes, truncated)
            } finally {
                opened.response.close()
            }
        }
    }

    sealed class OpenResult {
        data class Opened(val response: PluginHttpResponse, val finalUrl: URL) : OpenResult()
        data class Error(val reason: String) : OpenResult()
    }

    fun openFollowingRedirects(
        connector: PluginHttpConnector,
        allowlist: Set<String>,
        rawUrl: String,
        method: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        requestBody: ByteArray?
    ): OpenResult {
        val safeMethod = method.trim().uppercase().ifBlank { "GET" }
        if (safeMethod !in setOf("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH")) {
            return OpenResult.Error("unsupported_method")
        }
        val safeTimeout = timeoutMs.coerceIn(1000, MAX_TIMEOUT_MS)

        var url = PluginNetGuard.parseHttpUrl(rawUrl) ?: return OpenResult.Error("invalid_url")
        var hops = 0
        while (true) {
            val reason = validateHop(url, allowlist, connector)
            if (reason != null) return OpenResult.Error(reason)

            val resp = try {
                connector.open(url, safeMethod, headers, safeTimeout, requestBody)
            } catch (e: Exception) {
                return OpenResult.Error("request_failed: ${e.message}")
            }

            if (resp.status in 300..399) {
                val location = resp.headers.entries.firstOrNull { it.key.equals("Location", ignoreCase = true) }
                    ?.value?.firstOrNull()
                resp.close()
                if (location.isNullOrBlank() || hops >= PluginNetGuard.MAX_REDIRECTS) {
                    return OpenResult.Error("too_many_redirects")
                }
                val next = try { URL(url, location) } catch (_: Exception) { null }
                    ?.let { PluginNetGuard.parseHttpUrl(it.toString()) }
                    ?: return OpenResult.Error("invalid_redirect")
                url = next
                hops += 1
                continue
            }

            return OpenResult.Opened(resp, url)
        }
    }

    private fun readCapped(input: InputStream, cap: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        var truncated = false
        input.use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (total + n > cap) {
                    out.write(buf, 0, (cap - total).coerceAtLeast(0))
                    truncated = true
                    break
                }
                out.write(buf, 0, n)
                total += n
            }
        }
        return out.toByteArray() to truncated
    }

    fun successToJson(success: PluginNetResult.Success, responseType: String): JSONObject {
        val json = JSONObject()
        json.put("status", success.status)
        val headersJson = JSONObject()
        success.headers.forEach { (k, v) -> headersJson.put(k, v.joinToString(", ")) }
        json.put("headers", headersJson)
        json.put("truncated", success.truncated)
        if (responseType == "binary") {
            json.put("encoding", "base64")
            json.put("body", java.util.Base64.getEncoder().encodeToString(success.body))
        } else {
            json.put("encoding", "utf8")
            json.put("body", String(success.body, Charsets.UTF_8))
        }
        return json
    }
}
