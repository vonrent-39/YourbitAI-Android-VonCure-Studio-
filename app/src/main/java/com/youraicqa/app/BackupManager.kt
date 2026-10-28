package com.youraicqa.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupManager {

    class WrongPasswordException : Exception("Неверный пароль или повреждённый файл")

    private val MAGIC = "NANOAIBK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_LEN_BITS = 256

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    fun encrypt(json: JSONObject, password: String): ByteArray {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        return MAGIC + salt + iv + ciphertext
    }

    fun decrypt(bytes: ByteArray, password: String): JSONObject {
        if (bytes.size < MAGIC.size + SALT_LEN + IV_LEN + 1 ||
            !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw Exception("Это не файл резервной копии Yourbit AI")
        }
        var offset = MAGIC.size
        val salt = bytes.copyOfRange(offset, offset + SALT_LEN); offset += SALT_LEN
        val iv = bytes.copyOfRange(offset, offset + IV_LEN); offset += IV_LEN
        val ciphertext = bytes.copyOfRange(offset, bytes.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
            JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        } catch (e: Exception) {
            throw WrongPasswordException()
        }
    }

    data class ChatInfo(val id: String, val title: String, val messages: Int, val lastTs: Long)

    enum class ImportMode { COPY, SKIP_EXISTING, REPLACE }

    class ImportOptions(
        val mode: ImportMode = ImportMode.COPY,
        val markTitles: Boolean = false,
        val onTop: Boolean = false
    )

    class ImportResult(val added: Int, val replaced: Int, val skipped: Int) {
        val total: Int get() = added + replaced
    }

    private fun parseMessages(raw: String?): JSONArray =
        try { JSONArray(raw ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun lastTimestamp(msgs: JSONArray, fallbackId: String): Long {
        for (i in msgs.length() - 1 downTo 0) {
            val ts = msgs.optJSONObject(i)?.optLong("ts", 0L) ?: 0L
            if (ts > 0L) return ts
        }
        return fallbackId.toLongOrNull() ?: 0L
    }

    private fun takeLast(msgs: JSONArray, n: Int): JSONArray {
        if (n <= 0 || msgs.length() <= n) return msgs
        val out = JSONArray()
        for (i in msgs.length() - n until msgs.length()) out.put(msgs.get(i))
        return out
    }

    fun listLocalChats(context: Context): List<ChatInfo> {
        val prefs = context.getSharedPreferences("nanoai", Context.MODE_PRIVATE)
        val index = parseMessages(prefs.getString("chats_index", "[]"))
        val out = ArrayList<ChatInfo>()
        for (i in 0 until index.length()) {
            val o = index.optJSONObject(i) ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val msgs = parseMessages(prefs.getString("chat_${id}_messages", "[]"))
            out.add(ChatInfo(id, o.optString("title", "Новый чат"), msgs.length(), lastTimestamp(msgs, id)))
        }
        return out.sortedByDescending { it.lastTs }
    }

    /** lastN = 0 keeps all messages, otherwise only the last N per chat. */
    fun collectChats(context: Context, chatIds: Set<String>? = null, lastN: Int = 0): JSONObject {
        val prefs = context.getSharedPreferences("nanoai", Context.MODE_PRIVATE)
        val fullIndex = JSONArray(prefs.getString("chats_index", "[]"))
        val index = if (chatIds == null) fullIndex else JSONArray().apply {
            for (i in 0 until fullIndex.length()) {
                val entry = fullIndex.optJSONObject(i) ?: continue
                if (chatIds.contains(entry.optString("id"))) put(entry)
            }
        }
        val chats = JSONObject()
        for (i in 0 until index.length()) {
            val id = index.optJSONObject(i)?.optString("id") ?: continue
            val raw = prefs.getString("chat_${id}_messages", "[]") ?: "[]"
            chats.put(id, if (lastN > 0) takeLast(parseMessages(raw), lastN).toString() else raw)
        }
        return JSONObject()
            .put("type", "chats")
            .put("app", "Yourbit AI")
            .put("formatVersion", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("chatsIndex", index)
            .put("chats", chats)
    }

    fun listChatsInBackup(backup: JSONObject): List<Pair<String, String>> =
        describeChatsInBackup(backup).map { it.id to it.title }

    fun describeChatsInBackup(backup: JSONObject): List<ChatInfo> {
        val incomingIndex = backup.optJSONArray("chatsIndex") ?: JSONArray()
        val incomingChats = backup.optJSONObject("chats") ?: JSONObject()
        return (0 until incomingIndex.length()).mapNotNull { i ->
            val entry = incomingIndex.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val msgs = parseMessages(incomingChats.optString(id, "[]"))
            ChatInfo(id, entry.optString("title", "Чат"), msgs.length(), lastTimestamp(msgs, id))
        }.sortedByDescending { it.lastTs }
    }

    private val IMG_DATA = Regex("data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+")

    private fun messageText(m: JSONObject): String {
        val shown = m.optString("display_text", "")
        val raw = if (shown.isNotBlank()) shown else when (val c = m.opt("content")) {
            is String -> c
            is JSONArray -> buildString {
                for (j in 0 until c.length()) {
                    val p = c.optJSONObject(j) ?: continue
                    when (p.optString("type")) {
                        "text" -> append(p.optString("text"))
                        "image_url", "image" -> append("\n").append(Loc.t("[изображение]")).append("\n")
                    }
                }
            }
            else -> ""
        }
        return raw
            .replace(IMG_DATA, Loc.t("[изображение]"))
            .replace("[[IMG_GEN]]", Loc.t("[сгенерированное изображение] "))
            .trim()
    }

// Readable export only: attachments are not included.
    fun chatsToMarkdown(json: JSONObject, withTimestamps: Boolean): String {
        val dateFmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
        val index = json.optJSONArray("chatsIndex") ?: JSONArray()
        val chats = json.optJSONObject("chats") ?: JSONObject()
        val sb = StringBuilder()
        sb.append("# ").append(Loc.t("Yourbit AI — экспорт чатов")).append("\n\n")
        sb.append(Loc.t("Экспортировано")).append(": ")
            .append(dateFmt.format(java.util.Date(json.optLong("exportedAt", System.currentTimeMillis()))))
            .append(" · ").append(Loc.t("Чатов")).append(": ").append(index.length()).append("\n")
        for (i in 0 until index.length()) {
            val entry = index.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            val msgs = parseMessages(chats.optString(id, "[]"))
            sb.append("\n---\n\n## ").append(entry.optString("title", "Чат").ifBlank { Loc.t("Чат") }).append("\n\n")
            for (j in 0 until msgs.length()) {
                val m = msgs.optJSONObject(j) ?: continue
                val role = when (m.optString("role")) {
                    "user" -> Loc.t("Вы")
                    "assistant" -> Loc.t("ИИ")
                    "system" -> Loc.t("Система")
                    else -> continue
                }
                val text = messageText(m)
                if (text.isBlank()) continue
                sb.append("**").append(role).append("**")
                val ts = m.optLong("ts", 0L)
                if (withTimestamps && ts > 0L) sb.append(" · ").append(dateFmt.format(java.util.Date(ts)))
                sb.append("\n\n").append(text).append("\n\n")
            }
        }
        return sb.toString()
    }

    /** Plain exports start with "{"; encrypted backups start with NANOAIBK1. */
    fun isPlainJson(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
        return i < bytes.size && bytes[i] == '{'.code.toByte()
    }

    fun parsePlain(bytes: ByteArray): JSONObject {
        try {
            return JSONObject(String(bytes, Charsets.UTF_8).trimStart('\uFEFF'))
        } catch (_: Exception) {
            throw Exception("Файл повреждён или это не JSON Yourbit AI")
        }
    }

    fun importChats(
        context: Context,
        backup: JSONObject,
        chatIds: Set<String>? = null,
        opts: ImportOptions = ImportOptions()
    ): ImportResult {
        val prefs = context.getSharedPreferences("nanoai", Context.MODE_PRIVATE)
        val index = JSONArray(prefs.getString("chats_index", "[]"))
        val existingIds = (0 until index.length())
            .mapNotNull { index.optJSONObject(it)?.optString("id") }
            .toMutableSet()
        val incomingIndex = backup.optJSONArray("chatsIndex") ?: JSONArray()
        val incomingChats = backup.optJSONObject("chats") ?: JSONObject()
        val editor = prefs.edit()
        // Chat ids are creation timestamps (the list is sorted by them), so new ids are fresh timestamps.
        var cursor = System.currentTimeMillis()
        fun freshId(): String {
            while (existingIds.contains(cursor.toString())) cursor++
            return (cursor++).toString()
        }
        val mark = Loc.t("(импорт)")
        var added = 0; var replaced = 0; var skipped = 0
        for (i in 0 until incomingIndex.length()) {
            val entry = incomingIndex.optJSONObject(i) ?: continue
            val originalId = entry.optString("id")
            if (originalId.isBlank()) continue
            if (chatIds != null && !chatIds.contains(originalId)) continue
            val exists = existingIds.contains(originalId)
            val msgs = parseMessages(incomingChats.optString(originalId, "[]")).toString()
            val baseTitle = entry.optString("title", "").ifBlank { Loc.t("Чат") }

            if (exists && opts.mode == ImportMode.SKIP_EXISTING) { skipped++; continue }
            if (exists && opts.mode == ImportMode.REPLACE) {
                for (k in 0 until index.length()) {
                    val o = index.optJSONObject(k) ?: continue
                    if (o.optString("id") == originalId) { o.put("title", baseTitle); break }
                }
                editor.putString("chat_${originalId}_messages", msgs)
                editor.putBoolean("chat_${originalId}_titled", true)
                replaced++
                continue
            }
            val keepId = !exists && !opts.onTop && originalId.toLongOrNull() != null
            val newId = if (keepId) originalId else freshId()
            existingIds.add(newId)
            val title = if (opts.markTitles) "$baseTitle $mark" else baseTitle
            index.put(JSONObject().put("id", newId).put("title", title))
            editor.putString("chat_${newId}_messages", msgs)
            editor.putBoolean("chat_${newId}_titled", true)
            added++
        }
        editor.putString("chats_index", index.toString())
        editor.apply()
        return ImportResult(added, replaced, skipped)
    }

    private const val LIBRARY_FILE_MAX_BYTES = 25 * 1024 * 1024

    private fun libraryDir(context: Context): File =
        File(context.filesDir, "NanoAI/Files").apply { mkdirs() }

    private fun avatarsDir(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    private fun collectAvatarFiles(context: Context): JSONObject {
        val out = JSONObject()
        val files = avatarsDir(context).listFiles()?.filter { it.isFile }.orEmpty()
        for (f in files) {
            try {
                out.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun restoreAvatarFiles(context: Context, avatarFiles: JSONObject) {
        val dir = avatarsDir(context)
        dir.listFiles()?.forEach { it.delete() }
        for (name in avatarFiles.keys()) {
            try {
                val bytes = Base64.decode(avatarFiles.getString(name), Base64.NO_WRAP)
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                File(dir, safeName).writeBytes(bytes)
            } catch (_: Exception) {
            }
        }
    }

    private fun collectLibraryFiles(context: Context): Pair<JSONObject, List<String>> {
        val out = JSONObject()
        val skipped = mutableListOf<String>()
        val files = libraryDir(context).listFiles()?.filter { it.isFile }.orEmpty()
        for (f in files) {
            if (f.length() > LIBRARY_FILE_MAX_BYTES) {
                skipped.add(f.name)
                continue
            }
            try {
                val bytes = f.readBytes()
                out.put(f.name, Base64.encodeToString(bytes, Base64.NO_WRAP))
            } catch (_: Exception) {
                skipped.add(f.name)
            }
        }
        return out to skipped
    }

    private fun restoreLibraryFiles(context: Context, libraryFiles: JSONObject) {
        val dir = libraryDir(context)
        dir.listFiles()?.forEach { it.delete() }
        for (name in libraryFiles.keys()) {
            try {
                val bytes = Base64.decode(libraryFiles.getString(name), Base64.NO_WRAP)
                val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                File(dir, safeName).writeBytes(bytes)
            } catch (_: Exception) {
            }
        }
    }

    private val EXTRA_ROOTS = listOf("NanoAI/agent_chats", "NanoAI/gifs", "NanoAI/arena_img", "NanoAI/edited", "fonts", "plugin_files")
    private val LOCAL_ONLY_PREFS = setOf("nanoai_cloud")

    private fun backupPrefsNames(context: Context): Set<String> {
        val names = mutableSetOf("nanoai", "nanoai_plugin_app_settings", "nanoai_plugin_ai")
        try {
            File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { f ->
                if (f.name.endsWith(".xml")) names.add(f.name.removeSuffix(".xml"))
            }
        } catch (_: Exception) {}
        try { for (pl in PluginStore.load(context)) names.add("plugin_kv_${pl.id}") } catch (_: Exception) {}
        names.removeAll(LOCAL_ONLY_PREFS)
        return names
    }

    /** Server API keys are encrypted with a device-bound key, so they travel in plain text inside the encrypted backup. */
    private fun exportServerKeys(raw: String): String = try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optJSONObject("key") ?: continue
            val plain = ServerKeyCipher.decrypt(o.optString("id"), ServerKeyCipher.Blob(k.optString("iv"), k.optString("ct"))) ?: continue
            o.remove("key")
            o.put("keyPlain", plain)
        }
        arr.toString()
    } catch (_: Exception) { raw }

    private fun importServerKeys(raw: String): String = try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val plain = o.optString("keyPlain").takeIf { it.isNotBlank() } ?: continue
            o.remove("keyPlain")
            val blob = ServerKeyCipher.encrypt(o.optString("id"), plain) ?: continue
            o.put("key", JSONObject().put("iv", blob.iv).put("ct", blob.ct))
        }
        arr.toString()
    } catch (_: Exception) { raw }

    private fun collectExtraFiles(context: Context, skipped: MutableList<String>): JSONObject {
        val out = JSONObject()
        val base = context.filesDir
        for (root in EXTRA_ROOTS) {
            File(base, root).walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(base).invariantSeparatorsPath
                if (f.length() > LIBRARY_FILE_MAX_BYTES) { skipped.add(rel); return@forEach }
                try { out.put(rel, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)) } catch (_: Exception) { skipped.add(rel) }
            }
        }
        return out
    }

    private fun restoreExtraFiles(context: Context, files: JSONObject) {
        val base = context.filesDir
        val baseCanon = base.canonicalPath
        for (root in EXTRA_ROOTS) try { File(base, root).deleteRecursively() } catch (_: Exception) {}
        for (rel in files.keys()) {
            try {
                if (EXTRA_ROOTS.none { rel.startsWith("$it/") }) continue
                val target = File(base, rel)
                if (!target.canonicalPath.startsWith(baseCanon + File.separator)) continue
                target.parentFile?.mkdirs()
                target.writeBytes(Base64.decode(files.getString(rel), Base64.NO_WRAP))
            } catch (_: Exception) {}
        }
    }

    fun collectAccount(context: Context): JSONObject {
        SessionsManager.registerCurrentSession(context)
        val prefsFiles = JSONObject()
        for (name in backupPrefsNames(context)) {
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (name != "nanoai" && p.all.isEmpty()) continue
            val ser = serializePrefs(p)
            if (name == "nanoai") ser.optJSONObject("custom_servers")?.let { it.put("v", exportServerKeys(it.optString("v"))) }
            prefsFiles.put(name, ser)
        }
        val (libraryFiles, libSkipped) = collectLibraryFiles(context)
        val skipped = libSkipped.toMutableList()
        val extraFiles = collectExtraFiles(context, skipped)
        return JSONObject()
            .put("type", "account")
            .put("app", "Yourbit AI")
            .put("formatVersion", 3)
            .put("exportedAt", System.currentTimeMillis())
            .put("prefsFiles", prefsFiles)
            .put("libraryFiles", libraryFiles)
            .put("extraFiles", extraFiles)
            .put("skippedLibraryFiles", JSONArray(skipped))
            .put("avatarFiles", collectAvatarFiles(context))
    }

    fun skippedLibraryFiles(backup: JSONObject): List<String> {
        val arr = backup.optJSONArray("skippedLibraryFiles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    fun importAccount(context: Context, backup: JSONObject) {
        val prefsFiles = backup.optJSONObject("prefsFiles") ?: JSONObject()
        for (name in prefsFiles.keys()) {
            if (name in LOCAL_ONLY_PREFS) continue
            val entries = prefsFiles.getJSONObject(name)
            if (name == "nanoai") entries.optJSONObject("custom_servers")?.let { it.put("v", importServerKeys(it.optString("v"))) }
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            restorePrefs(editor, entries)
            editor.commit()
        }
        backup.optJSONObject("libraryFiles")?.let { restoreLibraryFiles(context, it) }
        backup.optJSONObject("extraFiles")?.let { restoreExtraFiles(context, it) }
        backup.optJSONObject("avatarFiles")?.let { restoreAvatarFiles(context, it) }
        SessionsManager.resetMySessionId(context)
        SessionsManager.registerCurrentSession(context)
    }

    private const val WIPE_MARKER = ".wipe_pending"

    private fun wipeMarker(app: Context) = File(app.applicationInfo.dataDir, WIPE_MARKER)

    fun wipeLocalAccount(context: Context) {
        val app = context.applicationContext
        try { wipeMarker(app).writeText("1") } catch (_: Exception) {}
        val names = mutableSetOf("nanoai", "nanoai_cloud", "nanoai_plugin_ai", "nanoai_plugin_app_settings")
        try { for (pl in PluginStore.load(app)) names.add("plugin_kv_${pl.id}") } catch (_: Exception) {}
        try {
            File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { f ->
                if (f.name.endsWith(".xml")) names.add(f.name.removeSuffix(".xml"))
            }
        } catch (_: Exception) {}
        for (n in names) {
            try { app.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Exception) {}
        }
        wipeFiles(app)
    }

    private fun wipeFiles(app: Context) {
        val dataDir = File(app.applicationInfo.dataDir)
        val dirs = mutableListOf<File?>(
            app.filesDir, app.cacheDir, app.codeCacheDir, app.noBackupFilesDir,
            File(dataDir, "databases"), File(dataDir, "app_webview")
        )
        try { dirs.addAll(app.getExternalFilesDirs(null)) } catch (_: Exception) {}
        try { dirs.addAll(app.externalCacheDirs) } catch (_: Exception) {}
        for (d in dirs) {
            try { d?.listFiles()?.forEach { it.deleteRecursively() } } catch (_: Exception) {}
        }
    }

    /** Finishes a wipe that was interrupted or overwritten by late writes of the old process. Runs before any prefs are read. */
    fun runPendingWipe(app: Context) {
        val marker = wipeMarker(app)
        if (!marker.exists()) return
        try { File(app.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        wipeFiles(app)
        marker.delete()
    }

    fun clearPendingWipe(app: Context) {
        try { wipeMarker(app).delete() } catch (_: Exception) {}
    }

    private fun serializePrefs(prefs: SharedPreferences): JSONObject {
        val out = JSONObject()
        for ((k, v) in prefs.all) {
            val entry = JSONObject()
            when (v) {
                is String -> entry.put("t", "s").put("v", v)
                is Boolean -> entry.put("t", "b").put("v", v)
                is Int -> entry.put("t", "i").put("v", v)
                is Long -> entry.put("t", "l").put("v", v)
                is Float -> entry.put("t", "f").put("v", v.toDouble())
                is Set<*> -> entry.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            out.put(k, entry)
        }
        return out
    }

    private fun restorePrefs(editor: SharedPreferences.Editor, json: JSONObject) {
        for (k in json.keys()) {
            val entry = json.optJSONObject(k) ?: continue
            when (entry.optString("t")) {
                "s" -> editor.putString(k, entry.optString("v"))
                "b" -> editor.putBoolean(k, entry.optBoolean("v"))
                "i" -> editor.putInt(k, entry.optInt("v"))
                "l" -> editor.putLong(k, entry.optLong("v"))
                "f" -> editor.putFloat(k, entry.optDouble("v").toFloat())
                "set" -> {
                    val arr = entry.optJSONArray("v") ?: JSONArray()
                    val set = mutableSetOf<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    editor.putStringSet(k, set)
                }
            }
        }
    }
}
