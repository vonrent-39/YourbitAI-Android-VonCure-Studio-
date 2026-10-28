package com.youraicqa.app

import java.util.UUID
import org.json.JSONObject

enum class PluginTaskState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class PluginTask(
    val id: String,
    val name: String,
    val state: PluginTaskState,
    val progress: Int,
    val error: String? = null,
    val result: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("state", state.name.lowercase())
        .put("progress", progress)
        .put("error", error)
        .put("result", result)
}

class PluginTaskRegistry {
    private val tasks = LinkedHashMap<String, PluginTask>()
    private val lock = Any()

    private val maxTasks = 200

    fun create(name: String): PluginTask = synchronized(lock) {
        if (tasks.size >= maxTasks) {
            val evict = tasks.values.firstOrNull { it.state != PluginTaskState.RUNNING }?.id
                ?: tasks.keys.firstOrNull()
            evict?.let { tasks.remove(it) }
        }
        val id = UUID.randomUUID().toString()
        val safeName = name.take(200).ifBlank { "task" }
        val task = PluginTask(id, safeName, PluginTaskState.RUNNING, 0)
        tasks[id] = task
        task
    }

    fun progress(id: String, percent: Int): PluginTask? = synchronized(lock) {
        val current = tasks[id] ?: return@synchronized null
        if (current.state != PluginTaskState.RUNNING) return@synchronized current
        val updated = current.copy(progress = percent.coerceIn(0, 100))
        tasks[id] = updated
        updated
    }

    fun complete(id: String): PluginTask? = terminal(id, PluginTaskState.COMPLETED, null, progress = 100)

    fun fail(id: String, error: String?): PluginTask? =
        terminal(id, PluginTaskState.FAILED, error?.take(500) ?: "error")

    fun cancel(id: String): PluginTask? = terminal(id, PluginTaskState.CANCELLED, null)

    fun setResult(id: String, json: String): PluginTask? = synchronized(lock) {
        val current = tasks[id] ?: return@synchronized null
        val updated = current.copy(result = json)
        tasks[id] = updated
        updated
    }

    fun isCancelled(id: String): Boolean = synchronized(lock) {
        tasks[id]?.state == PluginTaskState.CANCELLED
    }

    fun get(id: String): PluginTask? = synchronized(lock) { tasks[id] }

    fun all(): List<PluginTask> = synchronized(lock) { tasks.values.toList() }

    fun clear() = synchronized(lock) { tasks.clear() }

    private fun terminal(id: String, state: PluginTaskState, error: String?, progress: Int? = null): PluginTask? =
        synchronized(lock) {
            val current = tasks[id] ?: return@synchronized null
            if (current.state != PluginTaskState.RUNNING) return@synchronized current
            val updated = current.copy(
                state = state,
                progress = progress ?: current.progress,
                error = error
            )
            tasks[id] = updated
            updated
        }
}
