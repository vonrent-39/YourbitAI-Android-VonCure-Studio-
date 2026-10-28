package com.youraicqa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginFileNamesTest {

    @Test
    fun `plain filename passes through`() {
        assertEquals("result.txt", PluginFileNames.sanitize("result.txt"))
    }

    @Test
    fun `path traversal is stripped to bare filename`() {
        assertEquals("passwd", PluginFileNames.sanitize("../../../etc/passwd"))
        assertEquals("shadow", PluginFileNames.sanitize("..\\..\\windows\\shadow"))
    }

    @Test
    fun `dot and dotdot alone are rejected`() {
        assertNull(PluginFileNames.sanitize("."))
        assertNull(PluginFileNames.sanitize(".."))
    }

    @Test
    fun `blank and empty are rejected`() {
        assertNull(PluginFileNames.sanitize(""))
        assertNull(PluginFileNames.sanitize("   "))
    }

    @Test
    fun `dangerous characters are replaced not left intact`() {
        val out = PluginFileNames.sanitize("weird:name?.txt")
        assertEquals("weird_name_.txt", out)
    }

    @Test
    fun `overlong name is truncated`() {
        val longName = "a".repeat(500) + ".dat"
        val out = PluginFileNames.sanitize(longName)
        assertEquals(180, out!!.length)
    }
}
