package com.youraicqa.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPermissionParseTest {

    @Test
    fun `dotted manifest permission name resolves to the matching enum entry`() {
        val set = PluginPermission.parseSet(JSONArray(listOf("ui.theme")))
        assertEquals(setOf(PluginPermission.UI_THEME), set)
    }

    @Test
    fun `existing ALL_CAPS underscore names still parse unchanged`() {
        val set = PluginPermission.parseSet(JSONArray(listOf("UI_THEME", "QUICK_ACTIONS", "BACKGROUND_TASK")))
        assertEquals(setOf(PluginPermission.UI_THEME, PluginPermission.QUICK_ACTIONS, PluginPermission.BACKGROUND_TASK), set)
    }

    @Test
    fun `mixed case and dotted names both resolve`() {
        val set = PluginPermission.parseSet(JSONArray(listOf("Quick.Actions", "ui_theme")))
        assertEquals(setOf(PluginPermission.QUICK_ACTIONS, PluginPermission.UI_THEME), set)
    }

    @Test
    fun `unknown permission names are silently dropped, not throw`() {
        val set = PluginPermission.parseSet(JSONArray(listOf("UI_THEME", "made.up.permission", "")))
        assertEquals(setOf(PluginPermission.UI_THEME), set)
    }

    @Test
    fun `null or empty array yields empty set`() {
        assertTrue(PluginPermission.parseSet(null).isEmpty())
        assertTrue(PluginPermission.parseSet(JSONArray()).isEmpty())
    }
}
