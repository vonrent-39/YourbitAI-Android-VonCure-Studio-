package com.youraicqa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginTaskRegistryTest {

    @Test
    fun `create starts task in running state with zero progress`() {
        val reg = PluginTaskRegistry()
        val task = reg.create("op")
        assertEquals(PluginTaskState.RUNNING, task.state)
        assertEquals(0, task.progress)
        assertTrue(task.id.isNotBlank())
    }

    @Test
    fun `progress updates and clamps to 0-100`() {
        val reg = PluginTaskRegistry()
        val task = reg.create("op")
        reg.progress(task.id, 50)
        assertEquals(50, reg.get(task.id)?.progress)
        reg.progress(task.id, 500)
        assertEquals(100, reg.get(task.id)?.progress)
        reg.progress(task.id, -20)
        assertEquals(0, reg.get(task.id)?.progress)
    }

    @Test
    fun `complete sets terminal state and full progress`() {
        val reg = PluginTaskRegistry()
        val task = reg.create("op")
        reg.progress(task.id, 40)
        reg.complete(task.id)
        val result = reg.get(task.id)!!
        assertEquals(PluginTaskState.COMPLETED, result.state)
        assertEquals(100, result.progress)
    }

    @Test
    fun `fail sets terminal state with error message`() {
        val reg = PluginTaskRegistry()
        val task = reg.create("op")
        reg.fail(task.id, "boom")
        val result = reg.get(task.id)!!
        assertEquals(PluginTaskState.FAILED, result.state)
        assertEquals("boom", result.error)
    }

    @Test
    fun `cancel sets terminal state and isCancelled reports true`() {
        val reg = PluginTaskRegistry()
        val task = reg.create("op")
        assertTrue(!reg.isCancelled(task.id))
        reg.cancel(task.id)
        assertTrue(reg.isCancelled(task.id))
        assertEquals(PluginTaskState.CANCELLED, reg.get(task.id)?.state)
    }

    @Test
    fun `terminal task ignores further updates`() {
        val reg = PluginTaskRegistry()
        val task = reg.create("op")
        reg.complete(task.id)
        reg.progress(task.id, 10) // must be a no-op after completion
        reg.fail(task.id, "late error")
        val result = reg.get(task.id)!!
        assertEquals(PluginTaskState.COMPLETED, result.state)
        assertEquals(100, result.progress)
        assertNull(result.error)
    }

    @Test
    fun `unknown task id operations are no-ops and return null`() {
        val reg = PluginTaskRegistry()
        assertNull(reg.progress("missing", 50))
        assertNull(reg.complete("missing"))
        assertNull(reg.fail("missing", "x"))
        assertNull(reg.cancel("missing"))
        assertNull(reg.get("missing"))
    }

    @Test
    fun `setResult stores structured result data independent of state, even after terminal`() {
        val reg = PluginTaskRegistry()
        val task = reg.create("media-download")
        reg.setResult(task.id, """{"ok":true,"filename":"a.mp4"}""")
        assertEquals("""{"ok":true,"filename":"a.mp4"}""", reg.get(task.id)?.result)
        reg.complete(task.id)
        assertEquals("""{"ok":true,"filename":"a.mp4"}""", reg.get(task.id)?.result)
        reg.setResult(task.id, """{"ok":false}""")
        assertEquals("""{"ok":false}""", reg.get(task.id)?.result)
    }
}
