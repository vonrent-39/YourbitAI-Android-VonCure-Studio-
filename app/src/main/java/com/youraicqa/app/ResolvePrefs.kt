package com.youraicqa.app

import android.content.Context

object ResolvePrefs {
    private const val PREFS = "nanoai"
    private const val KEY_ENABLED = "resolve_enabled"
    private const val KEY_COUNT = "resolve_count"
    private fun slotKey(i: Int) = "resolve_slot_$i"

    const val MIN_COUNT = 2
    const val MAX_COUNT = 6
    const val DEFAULT_COUNT = 3
    const val MAX_CYCLES = 2

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) { prefs(c).edit().putBoolean(KEY_ENABLED, v).apply() }

    fun count(c: Context): Int = prefs(c).getInt(KEY_COUNT, DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT)
    fun setCount(c: Context, v: Int) { prefs(c).edit().putInt(KEY_COUNT, v.coerceIn(MIN_COUNT, MAX_COUNT)).apply() }

    fun slot(c: Context, index: Int): String = prefs(c).getString(slotKey(index), "").orEmpty()
    fun setSlot(c: Context, index: Int, key: String) { prefs(c).edit().putString(slotKey(index), key).apply() }

    fun chain(
        c: Context,
        primary: MainActivity.Model,
        all: List<MainActivity.Model>,
        usable: (MainActivity.Model) -> Boolean
    ): List<MainActivity.Model> {
        val want = count(c)
        val out = ArrayList<MainActivity.Model>(want)
        val seen = HashSet<String>()
        out.add(primary)
        seen.add(ModelAuthorityPrefs.keyOf(primary))
        for (i in 1 until want) {
            val k = slot(c, i)
            if (k.isBlank()) continue
            val m = all.firstOrNull { ModelAuthorityPrefs.keyOf(it) == k && !it.isImage } ?: continue
            if (!usable(m)) continue
            if (seen.add(ModelAuthorityPrefs.keyOf(m))) out.add(m)
        }
        if (out.size < want) {
            for (m in all) {
                if (out.size >= want) break
                if (!m.recommended || m.isImage || !usable(m)) continue
                if (seen.add(ModelAuthorityPrefs.keyOf(m))) out.add(m)
            }
        }
        return out
    }
}
