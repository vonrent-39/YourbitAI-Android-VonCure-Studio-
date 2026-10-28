package com.youraicqa.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCapabilitiesTest {

    @Test
    fun `null capabilities object yields NONE with no implied permissions`() {
        val caps = PluginCapabilities.parse(null)
        assertEquals(PluginCapabilities.NONE, caps)
        assertTrue(caps.impliedPermissions().isEmpty())
    }

    @Test
    fun `network capability permits all public servers and implies NETWORK permission`() {
        val json = JSONObject("""{"network":{"domains":["Example.com","API.example.com"]}}""")
        val caps = PluginCapabilities.parse(json)
        assertEquals(setOf("*"), caps.networkDomains)
        assertEquals(setOf(PluginPermission.NETWORK), caps.impliedPermissions())
    }

    @Test
    fun `download capability implies both DOWNLOAD and FILES permissions`() {
        val json = JSONObject("""{"download":{"domains":["cdn.example.com"]}}""")
        val caps = PluginCapabilities.parse(json)
        assertEquals(setOf(PluginPermission.DOWNLOAD, PluginPermission.FILES), caps.impliedPermissions())
    }

    @Test
    fun `backgroundTask and uiAction flags imply their permissions`() {
        val json = JSONObject("""{"backgroundTask":true,"uiAction":true}""")
        val caps = PluginCapabilities.parse(json)
        assertEquals(setOf(PluginPermission.BACKGROUND_TASK, PluginPermission.UI_ACTION), caps.impliedPermissions())
    }

    @Test
    fun `explicit network capability with no valid domains opens all public hosts`() {
        val json = JSONObject("""{"network":{"domains":["example.com/evil","bad domain.com",""]}}""")
        val caps = PluginCapabilities.parse(json)
        assertEquals(setOf("*"), caps.networkDomains)
        assertTrue(caps.hasNetwork)
        assertEquals(setOf(PluginPermission.NETWORK), caps.impliedPermissions())
    }

    @Test
    fun `empty declared download capability allows public servers and implies file saving`() {
        val caps = PluginCapabilities.parse(JSONObject("""{"download":{}}"""))
        assertEquals(setOf("*"), caps.downloadDomains)
        assertEquals(setOf(PluginPermission.DOWNLOAD, PluginPermission.FILES), caps.impliedPermissions())
    }
}
