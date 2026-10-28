package com.youraicqa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginStoreParseTest {

    @Test
    fun `old-style plugin without capabilities parses exactly as before`() {
        val text = """
            {
              "id": "my-theme",
              "name": "My Theme",
              "version": "1.0",
              "permissions": ["UI_THEME", "QUICK_ACTIONS"],
              "script": "nano.setTheme({});"
            }
        """.trimIndent()
        val plugin = PluginStore.parse(text)!!
        assertEquals(setOf(PluginPermission.UI_THEME, PluginPermission.QUICK_ACTIONS), plugin.permissions)
        assertEquals(PluginCapabilities.NONE, plugin.capabilities)
        assertTrue(plugin.capabilities.networkDomains.isEmpty())
        assertTrue((plugin.permissions - setOf(PluginPermission.UI_THEME, PluginPermission.QUICK_ACTIONS)).isEmpty())
    }

    @Test
    fun `plugin missing the permissions field entirely still parses with no permissions`() {
        val text = """{"id": "bare", "name": "Bare", "script": "1;"}"""
        val plugin = PluginStore.parse(text)!!
        assertTrue(plugin.permissions.isEmpty())
        assertEquals(PluginCapabilities.NONE, plugin.capabilities)
    }

    @Test
    fun `new-style plugin merges declared permissions with capability-implied ones`() {
        val text = """
            {
              "id": "nanosearch-unlocked",
              "name": "NanoSearch Unlocked",
              "version": "1.0.0",
              "permissions": ["UI_ACTION"],
              "capabilities": {
                "network": { "domains": ["httpbin.org"] },
                "download": { "domains": ["httpbin.org"] },
                "backgroundTask": true,
                "uiAction": true
              },
              "script": "nano.ui.action.register({});"
            }
        """.trimIndent()
        val plugin = PluginStore.parse(text)!!
        assertEquals(
            setOf(
                PluginPermission.UI_ACTION,
                PluginPermission.NETWORK,
                PluginPermission.DOWNLOAD,
                PluginPermission.FILES,
                PluginPermission.BACKGROUND_TASK
            ),
            plugin.permissions
        )
        assertEquals(setOf("httpbin.org"), plugin.capabilities.networkDomains)
        assertEquals(setOf("httpbin.org"), plugin.capabilities.downloadDomains)
    }

    @Test
    fun `plugin without an id is rejected`() {
        val text = """{"name": "No id"}"""
        assertNull(PluginStore.parse(text))
    }

    @Test
    fun `malformed json is rejected`() {
        assertNull(PluginStore.parse("{not json"))
    }

    @Test
    fun `removed media_processing permission is silently ignored`() {
        val text = """
            {
              "id": "legacy-media-plugin",
              "name": "Legacy",
              "permissions": ["media.processing"],
              "script": "1;"
            }
        """.trimIndent()
        val plugin = PluginStore.parse(text)!!
        assertTrue(plugin.permissions.isEmpty())
    }
}
