package com.youraicqa.app

import android.content.Context

object PluginsPrefs {
    private const val PREFS = "nanoai"
    private const val KEY_WEB_SEARCH = "plugin_web_search"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun webSearchEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WEB_SEARCH, true)

    fun setWebSearchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEB_SEARCH, enabled).apply()
    }
}
