package com.youraicqa.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PluginUiExtTest {
    @Test fun validExtensionIsParsed() {
        val e = UiExtension.parse("p1", JSONObject("""{"slot":"settings.tab","id":"x","title":"Моя вкладка","icon":"star","panel":"<b>hi</b>"}"""))
        assertNotNull(e)
        assertEquals("Моя вкладка", e!!.title)
        assertEquals("settings.tab", e.slot)
        assertEquals("p1", e.pluginId)
    }

    @Test fun unknownSlotIsRejected() {
        assertNull(UiExtension.parse("p1", JSONObject("""{"slot":"root.everything","id":"x"}""")))
    }

    @Test fun blankIdIsRejected() {
        assertNull(UiExtension.parse("p1", JSONObject("""{"slot":"chat.empty","id":"  "}""")))
    }

    @Test fun titleIsTrimmedAndLimited() {
        val e = UiExtension.parse("p1", JSONObject("""{"slot":"message.actions","id":"a","title":"${"x".repeat(200)}"}"""))
        assertEquals(UiExtension.MAX_TITLE, e!!.title.length)
    }

    @Test fun oversizedPanelIsRejected() {
        val big = "a".repeat(UiExtension.MAX_PANEL_CHARS + 1)
        assertNull(UiExtension.parse("p1", JSONObject().put("slot", "settings.tab").put("id", "a").put("panel", big)))
    }
}
