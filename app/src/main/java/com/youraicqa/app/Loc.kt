package com.youraicqa.app

import android.content.Context
import android.content.res.Configuration
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.Locale

object Loc {
    const val EN = "en"
    const val RU = "ru"
    const val ES = "es"
    const val PT = "pt"
    const val KK = "kk"
    const val UK = "uk"
    const val BE = "be"
    const val ZH = "zh"
    private const val PREFS = "nanoai"
    private const val KEY_LANG = "app_language"

    @Volatile private var current: String = EN
    @Volatile private var loaded = false
    private class Tpl(val regex: Regex, val out: String)
    private class Dict {
        val exact = HashMap<String, String>(2048)
        val templates = ArrayList<Tpl>()
    }
    private val en = Dict()
    private val es = Dict()
    private val pt = Dict()
    private val kk = Dict()
    private val uk = Dict()
    private val be = Dict()
    private val zh = Dict()
    private val DICT_LANGS: Map<String, Dict> = mapOf(ES to es, PT to pt, KK to kk, UK to uk, BE to be, ZH to zh)
    private val ALL_LANGS = setOf(EN, RU, ES, PT, KK, UK, BE, ZH)
    private fun normalize(v: String?): String = if (v != null && v in ALL_LANGS) v else EN
    private val cyr = Regex("[А-Яа-яЁё]")

    fun init(context: Context) {
        val app = context.applicationContext
        current = normalize(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, EN))
        if (!loaded) loadDictionary(app)
    }

    fun lang(context: Context): String {
        return normalize(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, EN))
    }

    fun setLang(context: Context, lang: String) {
        val v = normalize(lang)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LANG, v).apply()
        current = v
    }

    val isEnglish: Boolean get() = current == EN

    val isTranslating: Boolean get() = current != RU

    fun wrap(base: Context): Context {
        val tag = lang(base)
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }

    private fun loadDictionary(app: Context) {
        loadInto(app, "lang/en.tsv", en)
        loadInto(app, "lang/es.tsv", es)
        loadInto(app, "lang/pt.tsv", pt)
        loadInto(app, "lang/kk.tsv", kk)
        loadInto(app, "lang/uk.tsv", uk)
        loadInto(app, "lang/be.tsv", be)
        loadInto(app, "lang/zh.tsv", zh)
        loaded = true
    }

    private fun loadInto(app: Context, path: String, d: Dict) {
        try {
            app.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank() || line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val k = unescape(line.substring(0, tab))
                    val v = unescape(line.substring(tab + 1))
                    if (k.contains("{}")) {
                        val parts = k.split("{}").map { Regex.escape(it) }
                        d.templates.add(Tpl(Regex("^" + parts.joinToString("(.+?)") + "$", RegexOption.DOT_MATCHES_ALL), v))
                    } else {
                        d.exact[k] = v
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // Single pass so that "\\\\" is a literal backslash (e.g. "\\\\name") and is never read as "\\n" + "ame".
    private val ESC = Regex("\\\\([nt\\\\])")
    private fun unescape(s: String) = if (s.indexOf('\\') < 0) s else ESC.replace(s) {
        when (it.groupValues[1]) { "n" -> "\n"; "t" -> "\t"; else -> "\\" }
    }

    private fun lookup(ru: String): String? {
        if (current == EN) return lookupIn(en, ru)
        val own = DICT_LANGS[current] ?: return null
        // uk / be / kk readers fall back to the Russian source text, other languages to English.
        if (current == UK || current == BE || current == KK) return lookupIn(own, ru)
        return lookupIn(own, ru) ?: lookupIn(en, ru)
    }

    private fun lookupIn(d: Dict, ru: String): String? {
        d.exact[ru]?.let { return it }
        val trimmed = ru.trim()
        if (trimmed !== ru && trimmed.isNotEmpty()) {
            d.exact[trimmed]?.let { return ru.substringBefore(trimmed) + it + ru.substringAfterLast(trimmed) }
        }
        if (ru.length > 400) return null
        for (t in d.templates) {
            val m = t.regex.matchEntire(ru) ?: continue
            var out = t.out
            for (i in 1 until m.groupValues.size) {
                val piece = m.groupValues[i]
                out = out.replace("{$i}", lookup(piece) ?: piece)
            }
            return out
        }
        return null
    }

    fun t(ru: String): String {
        if (current == RU || !loaded) return ru
        if (!cyr.containsMatchIn(ru)) return ru
        return lookup(ru) ?: ru
    }

    fun tf(ru: String, vararg args: Any?): String = try {
        String.format(t(ru), *args)
    } catch (_: Exception) {
        String.format(ru, *args)
    }

    fun pick(ru: String, en: String): String {
        if (current == EN) return en
        val own = DICT_LANGS[current] ?: return ru
        return lookupIn(own, ru) ?: en
    }

    fun translateTree(root: View?) {
        if (root == null || current == RU || !loaded) return
        walk(root, 0)
    }

    private fun walk(v: View, depth: Int) {
        if (depth > 60) return
        v.contentDescription?.toString()?.let { cd ->
            if (cd.isNotEmpty() && cyr.containsMatchIn(cd)) lookup(cd)?.let { v.contentDescription = it }
        }
        if (v is TextView) {
            if (v !is EditText) {
                val t = v.text
                if (t != null && t.isNotEmpty() && t.length <= 1500 && !hasSpans(t) && cyr.containsMatchIn(t)) {
                    lookup(t.toString())?.let { v.text = it }
                }
            }
            val h = v.hint
            if (h != null && h.isNotEmpty() && cyr.containsMatchIn(h)) {
                lookup(h.toString())?.let { v.hint = it }
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
    }

    private fun hasSpans(t: CharSequence): Boolean =
        t is Spanned && t.getSpans(0, t.length, Any::class.java).isNotEmpty()
}
