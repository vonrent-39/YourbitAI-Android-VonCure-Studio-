package com.youraicqa.app

import org.json.JSONArray
import org.json.JSONObject

data class PluginCapabilities(
    val networkDomains: Set<String> = emptySet(),
    val downloadDomains: Set<String> = emptySet(),
    val files: Boolean = false,
    val backgroundTask: Boolean = false,
    val uiAction: Boolean = false,
    val aiProvider: Boolean = false
) {
    val hasNetwork get() = networkDomains.isNotEmpty()
    val hasDownload get() = downloadDomains.isNotEmpty()

    fun impliedPermissions(): Set<PluginPermission> = buildSet {
        if (hasNetwork) add(PluginPermission.NETWORK)
        if (hasDownload) {
            add(PluginPermission.DOWNLOAD)
            add(PluginPermission.FILES)
        }
        if (files) add(PluginPermission.FILES)
        if (backgroundTask) add(PluginPermission.BACKGROUND_TASK)
        if (uiAction) add(PluginPermission.UI_ACTION)
        if (aiProvider) { add(PluginPermission.AI_PROVIDER); add(PluginPermission.NETWORK) }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("network", JSONObject().put("domains", JSONArray(networkDomains.sorted())))
        put("download", JSONObject().put("domains", JSONArray(downloadDomains.sorted())))
        put("files", files)
        put("backgroundTask", backgroundTask)
        put("uiAction", uiAction)
        put("aiProvider", aiProvider)
    }

    companion object {
        val NONE = PluginCapabilities()

        fun parse(raw: JSONObject?): PluginCapabilities {
            if (raw == null) return NONE
            return PluginCapabilities(
                networkDomains = parseDomains(raw.optJSONObject("network")),
                downloadDomains = parseDomains(raw.optJSONObject("download")),
                files = raw.optBoolean("files", false),
                backgroundTask = raw.optBoolean("backgroundTask", false),
                uiAction = raw.optBoolean("uiAction", false),
                aiProvider = raw.optBoolean("aiProvider", false)
            )
        }

        private fun parseDomains(obj: JSONObject?): Set<String> {
            if (obj == null) return emptySet()
            return setOf("*")
        }
    }
}
