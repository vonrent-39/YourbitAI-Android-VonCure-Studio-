package com.youraicqa.app

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

object SessionsManager {
    private const val PREFS = "nanoai"
    private const val KEY_MY_ID = "device_session_id"
    private const val KEY_MY_LABEL = "device_session_label"
    private const val KEY_SESSIONS = "account_sessions"
    private const val KEY_REVOKED = "account_revoked_sessions"

    data class Session(val id: String, val label: String, val firstSeenAt: Long, val lastSeenAt: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun defaultLabel(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "устройство"
        val manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() && !model.startsWith(it, ignoreCase = true) }
        return if (manufacturer != null) "$manufacturer $model" else model
    }

    fun mySessionId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_MY_ID, null)?.let { return it }
        val bytes = ByteArray(9)
        SecureRandom().nextBytes(bytes)
        val id = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        p.edit().putString(KEY_MY_ID, id).apply()
        return id
    }

    fun resetMySessionId(context: Context) {
        prefs(context).edit().remove(KEY_MY_ID).commit()
    }

    fun myLabel(context: Context): String = prefs(context).getString(KEY_MY_LABEL, null) ?: defaultLabel()

    fun setMyLabel(context: Context, label: String) {
        prefs(context).edit().putString(KEY_MY_LABEL, label.ifBlank { defaultLabel() }).apply()
    }

    private fun readSessions(context: Context): JSONArray =
        JSONArray(prefs(context).getString(KEY_SESSIONS, "[]"))

    private fun writeSessions(context: Context, arr: JSONArray) {
        prefs(context).edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    fun registerCurrentSession(context: Context) {
        val id = mySessionId(context)
        val now = System.currentTimeMillis()
        val arr = readSessions(context)
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) {
                o.put("label", myLabel(context))
                o.put("lastSeenAt", now)
                found = true
                break
            }
        }
        if (!found) {
            arr.put(JSONObject()
                .put("id", id)
                .put("label", myLabel(context))
                .put("firstSeenAt", now)
                .put("lastSeenAt", now))
        }
        writeSessions(context, arr)
    }

    fun listSessions(context: Context): List<Session> {
        val arr = readSessions(context)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Session(id, o.optString("label", "Устройство"), o.optLong("firstSeenAt"), o.optLong("lastSeenAt"))
        }.sortedByDescending { it.lastSeenAt }
    }

    private fun readRevoked(context: Context): MutableSet<String> {
        val arr = JSONArray(prefs(context).getString(KEY_REVOKED, "[]"))
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toMutableSet()
    }

    private fun writeRevoked(context: Context, ids: Set<String>) {
        prefs(context).edit().putString(KEY_REVOKED, JSONArray(ids.toList()).toString()).apply()
    }

    fun revokedIds(context: Context): Set<String> = readRevoked(context)

    fun revoke(context: Context, sessionId: String) {
        val arr = readSessions(context)
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != sessionId) filtered.put(o)
        }
        writeSessions(context, filtered)
        val revoked = readRevoked(context)
        revoked.add(sessionId)
        writeRevoked(context, revoked)
    }

    fun isThisDeviceRevokedIn(context: Context, backup: JSONObject): Boolean {
        val nanoai = backup.optJSONObject("prefsFiles")?.optJSONObject("nanoai") ?: return false
        val revokedEntry = nanoai.optJSONObject(KEY_REVOKED) ?: return false
        if (revokedEntry.optString("t") != "s") return false
        val arr = try { JSONArray(revokedEntry.optString("v", "[]")) } catch (_: Exception) { return false }
        val myId = mySessionId(context)
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == myId) return true
        }
        return false
    }
}
