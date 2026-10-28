package com.youraicqa.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ProfileStore {
    private const val PREFS = "nanoai"
    private const val KEY_USER_NAME = "profile_user_name"
    private const val KEY_ASSISTANT_NAME = "profile_assistant_name"

    const val DEFAULT_USER_NAME = "Вы"
    const val DEFAULT_ASSISTANT_NAME = "Yourbit AI"

    private const val USER_AVATAR_FILE = "user.png"
    private const val ASSISTANT_AVATAR_FILE = "assistant.png"
    private const val AVATAR_SIZE_PX = 256

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun userName(context: Context): String =
        prefs(context).getString(KEY_USER_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_NAME

    fun setUserName(context: Context, name: String) {
        val trimmed = name.trim()
        val e = prefs(context).edit()
        if (trimmed.isEmpty() || trimmed == DEFAULT_USER_NAME) e.remove(KEY_USER_NAME) else e.putString(KEY_USER_NAME, trimmed)
        e.apply()
    }

    fun assistantName(context: Context): String =
        prefs(context).getString(KEY_ASSISTANT_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ASSISTANT_NAME

    fun setAssistantName(context: Context, name: String) {
        val trimmed = name.trim()
        val e = prefs(context).edit()
        if (trimmed.isEmpty() || trimmed == DEFAULT_ASSISTANT_NAME) e.remove(KEY_ASSISTANT_NAME) else e.putString(KEY_ASSISTANT_NAME, trimmed)
        e.apply()
    }

    fun avatarsDir(context: Context): File =
        File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }

    private fun avatarFile(context: Context, isUser: Boolean): File =
        File(avatarsDir(context), if (isUser) USER_AVATAR_FILE else ASSISTANT_AVATAR_FILE)

    fun hasCustomAvatar(context: Context, isUser: Boolean): Boolean = avatarFile(context, isUser).exists()

    fun avatarBitmap(context: Context, isUser: Boolean): Bitmap? {
        val f = avatarFile(context, isUser)
        if (!f.exists()) return null
        return try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null }
    }

    fun saveAvatarFromUri(context: Context, isUser: Boolean, uri: Uri): Boolean {
        return try {
            val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: return false
            val side = minOf(source.width, source.height)
            val x = (source.width - side) / 2
            val y = (source.height - side) / 2
            val square = Bitmap.createBitmap(source, x, y, side, side)
            val scaled = if (side != AVATAR_SIZE_PX) {
                Bitmap.createScaledBitmap(square, AVATAR_SIZE_PX, AVATAR_SIZE_PX, true)
            } else square
            FileOutputStream(avatarFile(context, isUser)).use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun resetAvatar(context: Context, isUser: Boolean) {
        runCatching { avatarFile(context, isUser).delete() }
    }
}
