package com.youraicqa.app.local

import org.json.JSONArray
import org.json.JSONObject

object LocalPromptTrim {
    private fun contentLen(o: JSONObject): Int {
        return when (val c = o.opt("content")) {
            is String -> c.length
            is JSONArray -> (0 until c.length()).sumOf { (c.optJSONObject(it)?.optString("text") ?: "").length }
            else -> 0
        }
    }

    fun trim(msgs: JSONArray, budgetChars: Int, extraDrop: Int): JSONArray? {
        val sys = ArrayList<JSONObject>()
        val rest = ArrayList<JSONObject>()
        for (i in 0 until msgs.length()) {
            val o = msgs.getJSONObject(i)
            if (o.optString("role") == "system") sys.add(o) else rest.add(o)
        }
        var total = sys.sumOf { contentLen(it) } + rest.sumOf { contentLen(it) }
        var start = 0
        while (start < rest.size - 1 && (total > budgetChars || start < extraDrop)) {
            total -= contentLen(rest[start]); start++
        }
        while (start > 0 && start < rest.size - 1 && rest[start].optString("role") == "assistant") {
            total -= contentLen(rest[start]); start++
        }
        if (start < extraDrop) return null
        val out = JSONArray()
        sys.forEach { out.put(it) }
        for (i in start until rest.size) out.put(rest[i])
        return out
    }
}
