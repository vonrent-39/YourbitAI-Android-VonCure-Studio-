package com.youraicqa.app

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object StatsPrefs {
    private const val PREFS = "nanoai"
    private const val KEY = "stats_v1"
    private const val KEEP_DAYS = 30

    const val MSG_SENT = "msgSent"
    const val REPLIES = "replies"
    const val ERRORS = "errors"
    const val TOK_IN = "tokIn"
    const val TOK_OUT = "tokOut"
    const val IMAGES = "images"
    const val IMAGE_ERRORS = "imageErrors"
    const val SEARCHES = "searches"
    const val CODE_REQ = "codeReq"
    const val LIKES = "likes"
    const val DISLIKES = "dislikes"
    const val FALLBACKS = "fallbacks"
    const val LATENCY_MS = "latencyMs"
    const val LATENCY_N = "latencyN"

    private val lock = Any()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(c: Context): JSONObject = try {
        JSONObject(prefs(c).getString(KEY, null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun dayKey(t: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(t))

    private fun bump(o: JSONObject, name: String, by: Long) {
        if (by == 0L) return
        o.put(name, o.optLong(name, 0L) + by)
    }

    private fun applyDeltas(c: Context, deltas: Map<String, Long>, model: String? = null, modelDeltas: Map<String, Long> = emptyMap()) {
        synchronized(lock) {
            val root = load(c)
            val total = root.optJSONObject("total") ?: JSONObject().also { root.put("total", it) }
            val days = root.optJSONObject("days") ?: JSONObject().also { root.put("days", it) }
            val today = days.optJSONObject(dayKey()) ?: JSONObject().also { days.put(dayKey(), it) }
            for ((k, v) in deltas) { bump(total, k, v); bump(today, k, v) }
            if (model != null && model.isNotBlank()) {
                val models = root.optJSONObject("models") ?: JSONObject().also { root.put("models", it) }
                val m = models.optJSONObject(model) ?: JSONObject().also { models.put(model, it) }
                for ((k, v) in modelDeltas) bump(m, k, v)
                if (models.length() > 60) {
                    val names = models.keys().asSequence().toList()
                    val weakest = names.minByOrNull { models.optJSONObject(it)?.optLong("req", 0L) ?: 0L }
                    if (weakest != null && weakest != model) models.remove(weakest)
                }
            }
            val keys = days.keys().asSequence().toList().sorted()
            if (keys.size > KEEP_DAYS) keys.take(keys.size - KEEP_DAYS).forEach { days.remove(it) }
            prefs(c).edit().putString(KEY, root.toString()).apply()
        }
    }

    fun recordMessageSent(c: Context, isCode: Boolean = false, isSearch: Boolean = false) {
        val d = hashMapOf(MSG_SENT to 1L)
        if (isCode) d[CODE_REQ] = 1L
        if (isSearch) d[SEARCHES] = 1L
        applyDeltas(c, d)
    }

    fun recordReply(c: Context, model: String, tokensIn: Int, tokensOut: Int, ms: Long) {
        applyDeltas(
            c,
            mapOf(REPLIES to 1L, TOK_IN to tokensIn.toLong(), TOK_OUT to tokensOut.toLong(), LATENCY_MS to ms, LATENCY_N to 1L),
            model,
            mapOf("req" to 1L, "tokIn" to tokensIn.toLong(), "tokOut" to tokensOut.toLong(), "ms" to ms)
        )
    }

    fun recordError(c: Context, model: String, tokensIn: Int = 0) {
        applyDeltas(c, mapOf(ERRORS to 1L, TOK_IN to tokensIn.toLong()), model, mapOf("req" to 1L, "err" to 1L))
    }

    fun recordModelFailure(c: Context, model: String) {
        applyDeltas(c, emptyMap(), model, mapOf("err" to 1L))
    }

    fun recordImage(c: Context, model: String, ok: Boolean, ms: Long) {
        if (ok) applyDeltas(c, mapOf(IMAGES to 1L), model, mapOf("req" to 1L, "ms" to ms))
        else applyDeltas(c, mapOf(IMAGE_ERRORS to 1L, ERRORS to 1L), model, mapOf("req" to 1L, "err" to 1L))
    }

    fun recordFeedback(c: Context, like: Boolean) {
        applyDeltas(c, mapOf((if (like) LIKES else DISLIKES) to 1L))
    }

    fun recordFallback(c: Context) {
        applyDeltas(c, mapOf(FALLBACKS to 1L))
    }

    fun reset(c: Context) {
        synchronized(lock) { prefs(c).edit().remove(KEY).apply() }
    }

    class ModelRow(val name: String, val requests: Long, val errors: Long, val tokens: Long, val avgMs: Long)

    class Snapshot(
        val total: Map<String, Long>,
        val period: Map<String, Long>,
        val daily: List<Triple<String, Long, Long>>,
        val models: List<ModelRow>
    )

    fun snapshot(c: Context, days: Int): Snapshot {
        val root = synchronized(lock) { load(c) }
        fun toMap(o: JSONObject?): Map<String, Long> {
            val out = HashMap<String, Long>()
            if (o == null) return out
            for (k in o.keys()) out[k] = o.optLong(k, 0L)
            return out
        }
        val total = toMap(root.optJSONObject("total"))
        val daysObj = root.optJSONObject("days") ?: JSONObject()
        val period = HashMap<String, Long>()
        val daily = ArrayList<Triple<String, Long, Long>>()
        val cal = Calendar.getInstance()
        val labelFmt = SimpleDateFormat("d.MM", Locale.US)
        val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val seq = ArrayList<Pair<String, String>>()
        for (i in days - 1 downTo 0) {
            val cc = Calendar.getInstance()
            cc.timeInMillis = cal.timeInMillis
            cc.add(Calendar.DAY_OF_YEAR, -i)
            seq.add(keyFmt.format(cc.time) to labelFmt.format(cc.time))
        }
        for ((key, label) in seq) {
            val d = daysObj.optJSONObject(key)
            if (d != null) for (k in d.keys()) period[k] = (period[k] ?: 0L) + d.optLong(k, 0L)
            daily.add(Triple(label, (d?.optLong(TOK_IN, 0L) ?: 0L) + (d?.optLong(TOK_OUT, 0L) ?: 0L), d?.optLong(MSG_SENT, 0L) ?: 0L))
        }
        val modelsObj = root.optJSONObject("models") ?: JSONObject()
        val rows = ArrayList<ModelRow>()
        for (name in modelsObj.keys()) {
            val m = modelsObj.optJSONObject(name) ?: continue
            val req = m.optLong("req", 0L)
            val okReq = (req - m.optLong("err", 0L)).coerceAtLeast(1L)
            rows.add(ModelRow(name, req, m.optLong("err", 0L), m.optLong("tokIn", 0L) + m.optLong("tokOut", 0L), m.optLong("ms", 0L) / okReq))
        }
        rows.sortByDescending { it.requests }
        return Snapshot(total, period, daily, rows)
    }
}
