package com.youraicqa.app

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNetGuardTest {

    @Test
    fun `exact domain is allowed`() {
        assertTrue(PluginNetGuard.isHostAllowed("example.com", setOf("example.com")))
    }

    @Test
    fun `subdomain is allowed`() {
        assertTrue(PluginNetGuard.isHostAllowed("api.example.com", setOf("example.com")))
    }

    @Test
    fun `unrelated domain is rejected`() {
        assertFalse(PluginNetGuard.isHostAllowed("evil.com", setOf("example.com")))
    }

    @Test
    fun `lookalike suffix domain is rejected`() {
        // "notexample.com" must NOT match allowlist entry "example.com".
        assertFalse(PluginNetGuard.isHostAllowed("notexample.com", setOf("example.com")))
    }

    @Test
    fun `empty allowlist rejects everything`() {
        assertFalse(PluginNetGuard.isHostAllowed("example.com", emptySet()))
    }

    @Test
    fun `wildcard allows any public hostname while address checks remain separate`() {
        assertTrue(PluginNetGuard.isHostAllowed("api.example.com", setOf("*")))
        assertTrue(PluginNetGuard.isHostAllowed("other.example.net", setOf("*")))
    }

    @Test
    fun `host matching is case insensitive`() {
        assertTrue(PluginNetGuard.isHostAllowed("API.Example.COM", setOf("example.com")))
    }

    @Test
    fun `http and https schemes parse`() {
        assertEquals("example.com", PluginNetGuard.parseHttpUrl("https://example.com/path")?.host)
        assertEquals("example.com", PluginNetGuard.parseHttpUrl("http://example.com")?.host)
    }

    @Test
    fun `non-http schemes are rejected`() {
        assertNull(PluginNetGuard.parseHttpUrl("file:///etc/passwd"))
        assertNull(PluginNetGuard.parseHttpUrl("javascript:alert(1)"))
        assertNull(PluginNetGuard.parseHttpUrl("content://com.evil/data"))
        assertNull(PluginNetGuard.parseHttpUrl("ftp://example.com/file"))
    }

    @Test
    fun `malformed urls are rejected`() {
        assertNull(PluginNetGuard.parseHttpUrl(""))
        assertNull(PluginNetGuard.parseHttpUrl("not a url"))
        assertNull(PluginNetGuard.parseHttpUrl("https://"))
    }

    @Test
    fun `loopback address is blocked`() {
        val addr = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        assertTrue(PluginNetGuard.isBlockedAddress(addr))
    }

    @Test
    fun `private network address is blocked`() {
        val addr = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5))
        assertTrue(PluginNetGuard.isBlockedAddress(addr))
        val addr2 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        assertTrue(PluginNetGuard.isBlockedAddress(addr2))
    }

    @Test
    fun `cloud metadata address is blocked`() {
        val addr = InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))
        assertTrue(PluginNetGuard.isBlockedAddress(addr))
    }

    @Test
    fun `public address is not blocked`() {
        val addr = InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte()))
        assertFalse(PluginNetGuard.isBlockedAddress(addr))
    }
}
