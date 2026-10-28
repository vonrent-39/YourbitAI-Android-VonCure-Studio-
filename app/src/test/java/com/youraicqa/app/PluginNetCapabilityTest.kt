package com.youraicqa.app

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeConnector : PluginHttpConnector {
    data class Scripted(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray, val throwOnOpen: Exception? = null)

    val responses = mutableMapOf<String, Scripted>()
    val resolutions = mutableMapOf<String, List<InetAddress>>()
    var openCount = 0

    private fun publicAddr() = InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))

    override fun resolve(host: String): List<InetAddress> = resolutions[host] ?: listOf(publicAddr())

    override fun open(url: URL, method: String, headers: Map<String, String>, timeoutMs: Int, body: ByteArray?): PluginHttpResponse {
        openCount += 1
        val scripted = responses[url.toString()] ?: throw IOException("no scripted response for $url")
        scripted.throwOnOpen?.let { throw it }
        return PluginHttpResponse(scripted.status, scripted.headers, ByteArrayInputStream(scripted.body), close = {})
    }
}

class PluginNetCapabilityTest {

    @Test
    fun `allowed domain returns success`() {
        val connector = FakeConnector()
        connector.responses["https://example.com/data"] =
            FakeConnector.Scripted(200, mapOf("Content-Type" to listOf("text/plain")), "hello".toByteArray())

        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://example.com/data", "GET", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )

        assertTrue(result is PluginNetResult.Success)
        result as PluginNetResult.Success
        assertEquals(200, result.status)
        assertEquals("hello", String(result.body))
        assertEquals(1, connector.openCount)
    }

    @Test
    fun `domain not in allowlist is rejected without opening a connection`() {
        val connector = FakeConnector()
        connector.responses["https://evil.com/steal"] = FakeConnector.Scripted(200, emptyMap(), ByteArray(0))

        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://evil.com/steal", "GET", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )

        assertTrue(result is PluginNetResult.Error)
        assertEquals("domain_not_allowed", (result as PluginNetResult.Error).reason)
        assertEquals(0, connector.openCount)
    }

    @Test
    fun `malformed url is rejected before touching the connector`() {
        val connector = FakeConnector()
        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "not a url", "GET", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )
        assertTrue(result is PluginNetResult.Error)
        assertEquals("invalid_url", (result as PluginNetResult.Error).reason)
        assertEquals(0, connector.openCount)
    }

    @Test
    fun `redirect to an allowed host is followed`() {
        val connector = FakeConnector()
        connector.responses["https://example.com/start"] =
            FakeConnector.Scripted(302, mapOf("Location" to listOf("https://example.com/final")), ByteArray(0))
        connector.responses["https://example.com/final"] =
            FakeConnector.Scripted(200, emptyMap(), "final-body".toByteArray())

        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://example.com/start", "GET", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )

        assertTrue(result is PluginNetResult.Success)
        assertEquals("final-body", String((result as PluginNetResult.Success).body))
        assertEquals(2, connector.openCount)
    }

    @Test
    fun `redirect outside the allowlist is blocked, not silently followed`() {
        val connector = FakeConnector()
        connector.responses["https://example.com/start"] =
            FakeConnector.Scripted(302, mapOf("Location" to listOf("https://attacker.com/steal")), ByteArray(0))

        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://example.com/start", "GET", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )

        assertTrue(result is PluginNetResult.Error)
        assertEquals("domain_not_allowed", (result as PluginNetResult.Error).reason)
        assertEquals(1, connector.openCount)
    }

    @Test
    fun `redirect loop is capped instead of looping forever`() {
        val connector = FakeConnector()
        connector.responses["https://example.com/a"] =
            FakeConnector.Scripted(302, mapOf("Location" to listOf("https://example.com/b")), ByteArray(0))
        connector.responses["https://example.com/b"] =
            FakeConnector.Scripted(302, mapOf("Location" to listOf("https://example.com/a")), ByteArray(0))

        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://example.com/a", "GET", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )

        assertTrue(result is PluginNetResult.Error)
        assertEquals("too_many_redirects", (result as PluginNetResult.Error).reason)
        assertTrue(connector.openCount <= PluginNetGuard.MAX_REDIRECTS + 1)
    }

    @Test
    fun `connector failure (eg timeout) surfaces as an error result, not a crash`() {
        val connector = FakeConnector()
        connector.responses["https://example.com/slow"] =
            FakeConnector.Scripted(0, emptyMap(), ByteArray(0), throwOnOpen = java.net.SocketTimeoutException("timed out"))

        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://example.com/slow", "GET", emptyMap(), 1000, null
        )
        assertTrue(result is PluginNetResult.Error)
        assertTrue((result as PluginNetResult.Error).reason.startsWith("request_failed"))
    }

    @Test
    fun `response larger than the cap is truncated, not buffered without bound`() {
        val connector = FakeConnector()
        val big = ByteArray(PluginNetCapability.MAX_BODY_BYTES + 1000) { 'x'.code.toByte() }
        connector.responses["https://example.com/big"] = FakeConnector.Scripted(200, emptyMap(), big)

        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://example.com/big", "GET", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )
        assertTrue(result is PluginNetResult.Success)
        result as PluginNetResult.Success
        assertTrue(result.truncated)
        assertEquals(PluginNetCapability.MAX_BODY_BYTES, result.body.size)
    }

    @Test
    fun `unsupported http method is rejected`() {
        val connector = FakeConnector()
        val result = PluginNetCapability.request(
            connector, setOf("example.com"), "https://example.com/x", "TRACE", emptyMap(),
            PluginNetCapability.DEFAULT_TIMEOUT_MS, null
        )
        assertTrue(result is PluginNetResult.Error)
        assertEquals("unsupported_method", (result as PluginNetResult.Error).reason)
    }
}
