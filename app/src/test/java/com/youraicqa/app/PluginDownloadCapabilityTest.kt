package com.youraicqa.app

import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeDownloadConnector(private val body: ByteArray, private val chunk: Int = 4096) : PluginHttpConnector {
    override fun resolve(host: String): List<InetAddress> =
        listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())))

    override fun open(url: URL, method: String, headers: Map<String, String>, timeoutMs: Int, requestBody: ByteArray?): PluginHttpResponse {
        return PluginHttpResponse(
            status = 200,
            headers = mapOf("Content-Length" to listOf(body.size.toString())),
            body = ByteArrayInputStream(body),
            close = {}
        )
    }
}

class PluginDownloadCapabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `allowed domain streams file into the plugin-private directory`() {
        val payload = "hello world".toByteArray()
        val connector = FakeDownloadConnector(payload)
        val outcome = PluginDownloadCapability.run(
            baseFilesDir = tmp.root,
            connector = connector,
            allowlist = setOf("example.com"),
            pluginId = "demo-plugin",
            rawUrl = "https://example.com/file.bin",
            filename = "file.bin",
            isCancelled = { false },
            onProgress = {}
        )
        assertTrue(outcome.ok)
        assertEquals(payload.size.toLong(), outcome.bytesWritten)
        val saved = File(PluginFiles.dirFor(tmp.root, "demo-plugin"), "file.bin")
        assertTrue(saved.exists())
        assertEquals("hello world", saved.readText())
    }

    @Test
    fun `disallowed domain is rejected and nothing is written`() {
        val connector = FakeDownloadConnector("secret".toByteArray())
        val outcome = PluginDownloadCapability.run(
            baseFilesDir = tmp.root,
            connector = connector,
            allowlist = setOf("example.com"),
            pluginId = "demo-plugin",
            rawUrl = "https://evil.com/file.bin",
            filename = "file.bin",
            isCancelled = { false },
            onProgress = {}
        )
        assertFalse(outcome.ok)
        assertEquals("domain_not_allowed", outcome.reason)
        assertFalse(File(PluginFiles.dirFor(tmp.root, "demo-plugin"), "file.bin").exists())
    }

    @Test
    fun `progress callback reflects Content-Length based percentage`() {
        val payload = ByteArray(200_000) { 1 }
        val connector = FakeDownloadConnector(payload)
        val progressValues = mutableListOf<Int>()
        val outcome = PluginDownloadCapability.run(
            baseFilesDir = tmp.root,
            connector = connector,
            allowlist = setOf("example.com"),
            pluginId = "demo-plugin",
            rawUrl = "https://example.com/big.bin",
            filename = "big.bin",
            isCancelled = { false },
            onProgress = { progressValues.add(it) }
        )
        assertTrue(outcome.ok)
        assertTrue(progressValues.isNotEmpty())
        assertEquals(100, progressValues.last())
        assertTrue(progressValues.all { it in 0..100 })
        assertTrue(progressValues.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `cancellation stops the download and removes the partial file`() {
        val payload = ByteArray(500_000) { 1 }
        val connector = FakeDownloadConnector(payload)
        var reads = 0
        val outcome = PluginDownloadCapability.run(
            baseFilesDir = tmp.root,
            connector = connector,
            allowlist = setOf("example.com"),
            pluginId = "demo-plugin",
            rawUrl = "https://example.com/big.bin",
            filename = "big.bin",
            isCancelled = { reads++; reads > 1 },
            onProgress = {}
        )
        assertFalse(outcome.ok)
        assertEquals("cancelled", outcome.reason)
        assertFalse(File(PluginFiles.dirFor(tmp.root, "demo-plugin"), "big.bin").exists())
        assertFalse(File(PluginFiles.dirFor(tmp.root, "demo-plugin"), "big.bin.part").exists())
    }

    @Test
    fun `filename is sanitized, no path traversal into the base directory`() {
        val connector = FakeDownloadConnector("data".toByteArray())
        val outcome = PluginDownloadCapability.run(
            baseFilesDir = tmp.root,
            connector = connector,
            allowlist = setOf("example.com"),
            pluginId = "demo-plugin",
            rawUrl = "https://example.com/data",
            filename = "../../../etc/passwd",
            isCancelled = { false },
            onProgress = {}
        )
        assertTrue(outcome.ok)
        val saved = File(PluginFiles.dirFor(tmp.root, "demo-plugin"), "passwd")
        assertTrue(saved.exists())
        assertFalse(File(tmp.root.parentFile, "etc/passwd").exists())
    }
}
