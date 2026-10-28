package com.youraicqa.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CustomServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val fallbackUrl: String = "",
    val apiKey: String = "",
    val streaming: Boolean = true,
    val trustSelfSigned: Boolean = false,
    val pinnedCertSha256: String = "",
    val hostConfirmed: Boolean = false,
    val models: List<String> = emptyList(),
    val keyLost: Boolean = false,
    val managedModels: List<String> = emptyList(),
    val enabled: Boolean = true
)

object ServerKeyCipher {
    private const val ANDROID_KS = "AndroidKeyStore"
    private const val ALIAS = "yourbit_servers_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    class Blob(val iv: String, val ct: String)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun aad(serverId: String) = ("yourbit-server:$serverId").toByteArray(Charsets.UTF_8)

    fun encrypt(serverId: String, plain: String): Blob? = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        c.updateAAD(aad(serverId))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Blob(Base64.encodeToString(c.iv, Base64.NO_WRAP), Base64.encodeToString(ct, Base64.NO_WRAP))
    } catch (_: Exception) { null }

    fun decrypt(serverId: String, blob: Blob): String? = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, Base64.decode(blob.iv, Base64.NO_WRAP)))
        c.updateAAD(aad(serverId))
        String(c.doFinal(Base64.decode(blob.ct, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) { null }
}

object ServerStore {
    private const val PREFS = "nanoai"
    private const val KEY = "custom_servers"
    private val lock = Any()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readRaw(context: Context): JSONArray {
        val raw = prefs(context).getString(KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun writeRaw(context: Context, arr: JSONArray) {
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun fromJson(o: JSONObject): CustomServer? {
        val base = o.optString("baseUrl").trim()
        if (base.isEmpty()) return null
        val id = o.optString("id").ifBlank { return null }
        var key = ""
        var lost = false
        val k = o.optJSONObject("key")
        if (k != null) {
            val dec = ServerKeyCipher.decrypt(id, ServerKeyCipher.Blob(k.optString("iv"), k.optString("ct")))
            if (dec == null) lost = true else key = dec
        }
        val models = o.optJSONArray("models")?.let { a ->
            (0 until a.length()).mapNotNull { i -> (a.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
        } ?: emptyList()
        return CustomServer(
            id = id,
            name = o.optString("name").ifBlank { base },
            baseUrl = base,
            fallbackUrl = o.optString("fallbackUrl").trim(),
            apiKey = key,
            streaming = o.optBoolean("streaming", true),
            trustSelfSigned = o.optBoolean("trustSelfSigned", false),
            pinnedCertSha256 = ServerLogic.normalizeFingerprint(o.optString("pinnedCertSha256")),
            hostConfirmed = o.optBoolean("hostConfirmed", false),
            models = models,
            keyLost = lost,
            enabled = o.optBoolean("enabled", true),
            managedModels = o.optJSONArray("managed")?.let { a ->
                (0 until a.length()).mapNotNull { i -> (a.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
            } ?: emptyList()
        )
    }

    private fun toJson(s: CustomServer): JSONObject = JSONObject()
        .put("id", s.id)
        .put("name", s.name)
        .put("baseUrl", s.baseUrl)
        .put("fallbackUrl", s.fallbackUrl)
        .put("streaming", s.streaming)
        .put("trustSelfSigned", s.trustSelfSigned)
        .put("pinnedCertSha256", s.pinnedCertSha256)
        .put("hostConfirmed", s.hostConfirmed)
        .put("enabled", s.enabled)
        .put("models", JSONArray(s.models))
        .put("managed", JSONArray(s.managedModels))

    fun newId(): String = UUID.randomUUID().toString()

    fun load(context: Context): List<CustomServer> = synchronized(lock) {
        val arr = readRaw(context)
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }
    }

    fun find(context: Context, id: String): CustomServer? = load(context).firstOrNull { it.id == id }

    fun add(context: Context, server: CustomServer) = put(context, server)

    fun update(context: Context, server: CustomServer) = put(context, server)

    private fun put(context: Context, server: CustomServer) = synchronized(lock) {
        val arr = readRaw(context)
        var idx = -1
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optString("id") == server.id) { idx = i; break }
        val obj = toJson(server)
        when {
            server.apiKey.isNotBlank() -> {
                val blob = ServerKeyCipher.encrypt(server.id, server.apiKey.trim())
                    ?: throw IllegalStateException("Не удалось зашифровать ключ: хранилище ключей Android недоступно")
                obj.put("key", JSONObject().put("iv", blob.iv).put("ct", blob.ct))
            }
            server.keyLost && idx >= 0 -> arr.optJSONObject(idx)?.optJSONObject("key")?.let { obj.put("key", it) }
        }
        if (idx >= 0) arr.put(idx, obj) else arr.put(obj)
        writeRaw(context, arr)
    }

    fun remove(context: Context, id: String) = synchronized(lock) {
        val src = readRaw(context)
        val out = JSONArray()
        for (i in 0 until src.length()) {
            val o = src.optJSONObject(i) ?: continue
            if (o.optString("id") != id) out.put(o)
        }
        writeRaw(context, out)
    }

    fun updateModels(context: Context, id: String, fetched: Result<List<String>>) = synchronized(lock) {
        val arr = readRaw(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            val cached = o.optJSONArray("models")?.let { a ->
                (0 until a.length()).mapNotNull { j -> (a.opt(j) as? String)?.takeIf { it.isNotBlank() } }
            } ?: emptyList()
            val next = ServerLogic.applyRefresh(cached, fetched)
            if (next != cached) {
                o.put("models", JSONArray(next))
                writeRaw(context, arr)
            }
            return@synchronized
        }
    }

    fun updateModelsInfo(context: Context, id: String, fetched: Result<ServerLogic.ParsedModels>): Boolean = synchronized(lock) {
        val ok = fetched.getOrNull() ?: return@synchronized false
        val arr = readRaw(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            fun strings(a: JSONArray?): List<String> =
                a?.let { x -> (0 until x.length()).mapNotNull { j -> (x.opt(j) as? String)?.takeIf { it.isNotBlank() } } } ?: emptyList()
            val cached = strings(o.optJSONArray("models"))
            val oldManaged = strings(o.optJSONArray("managed"))
            val nextIds = if (ok.ids.isEmpty()) cached else ok.ids
            val nextManaged = ok.managed.filter { it in nextIds }
            if (nextIds == cached && nextManaged == oldManaged) return@synchronized false
            o.put("models", JSONArray(nextIds))
            o.put("managed", JSONArray(nextManaged))
            writeRaw(context, arr)
            return@synchronized true
        }
        false
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) = synchronized(lock) {
        val arr = readRaw(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            o.put("enabled", enabled)
            writeRaw(context, arr)
            return@synchronized
        }
    }

    fun loadEnabled(context: Context): List<CustomServer> = load(context).filter { it.enabled }

    fun setHostConfirmed(context: Context, id: String, confirmed: Boolean) = synchronized(lock) {
        val arr = readRaw(context)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            o.put("hostConfirmed", confirmed)
            writeRaw(context, arr)
            return@synchronized
        }
    }
}
