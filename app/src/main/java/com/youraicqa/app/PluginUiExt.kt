package com.youraicqa.app

data class UiExtension(
    val pluginId: String,
    val slot: String,
    val id: String,
    val title: String,
    val icon: String,
    val panel: String,
    val prompt: String
) {
    companion object {
        const val SLOT_MESSAGE_ACTIONS = "message.actions"
        const val SLOT_CHAT_EMPTY = "chat.empty"
        const val SLOT_SETTINGS_TAB = "settings.tab"
        val SLOTS = setOf(SLOT_MESSAGE_ACTIONS, SLOT_CHAT_EMPTY, SLOT_SETTINGS_TAB)

        const val MAX_PER_PLUGIN = 12
        const val MAX_TITLE = 40
        const val MAX_PANEL_CHARS = 200_000
        const val MAX_PROMPT = 400

        fun iconRes(name: String): Int? = when (name.trim().lowercase()) {
            "copy" -> R.drawable.ic_copy
            "share" -> R.drawable.ic_share
            "edit", "pencil" -> R.drawable.ic_pencil_asset
            "refresh", "regenerate" -> R.drawable.ic_regenerate
            "like" -> R.drawable.ic_like
            "dislike" -> R.drawable.ic_dislike
            "trash", "delete" -> R.drawable.ic_trash
            "search" -> R.drawable.ic_search
            "bulb", "idea" -> R.drawable.ic_bulb
            "brain" -> R.drawable.ic_brain
            "bolt", "flash" -> R.drawable.ic_bolt
            "sparkles", "magic" -> R.drawable.ic_sparkles
            "chat" -> R.drawable.ic_empty_chat
            "heart", "determination" -> R.drawable.ic_determination
            "puzzle", "plugin" -> R.drawable.ic_puzzle
            "play" -> R.drawable.ic_play
            "download" -> R.drawable.ic_download
            "settings" -> R.drawable.ic_settings
            "globe" -> R.drawable.ic_globe
            "rocket" -> R.drawable.ic_rocket
            "palette" -> R.drawable.ic_palette
            "star", "trophy" -> R.drawable.ic_trophy
            else -> null
        }

        fun parse(pluginId: String, json: org.json.JSONObject): UiExtension? {
            val slot = json.optString("slot").trim()
            if (slot !in SLOTS) return null
            val id = json.optString("id").trim().take(64)
            if (id.isEmpty()) return null
            val title = json.optString("title").trim().ifEmpty { id }.take(MAX_TITLE)
            val icon = json.optString("icon").trim().take(24)
            val panel = json.optString("panel")
            if (panel.length > MAX_PANEL_CHARS) return null
            val prompt = json.optString("prompt").take(MAX_PROMPT)
            return UiExtension(pluginId, slot, id, title, icon, panel, prompt)
        }
    }
}
