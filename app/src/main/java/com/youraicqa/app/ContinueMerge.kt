package com.youraicqa.app

object ContinueMerge {
    private const val MIN_OVERLAP = 15
    private const val MAX_OVERLAP = 800
    private const val MIN_NORM = 30
    private const val MIN_NORM_INSIDE = 40

    // Appends a continuation to the previous reply and drops whatever the model repeated
    fun merge(prev: String, rawContinuation: String): String {
        if (rawContinuation.isBlank()) return prev
        if (prev.isBlank()) return rawContinuation
        val prevTrim = prev.trimEnd()
        var cont = rawContinuation

        // Compare without whitespace and * _ so a rewritten answer is not appended twice
        val np = normalize(prevTrim)
        val nc = normalize(cont)
        if (np.length >= MIN_NORM && nc.isNotEmpty()) {
            if (nc.length >= np.length && nc.startsWith(np)) {
                val rest = cont.substring(consumeNormalized(cont, np.length))
                return if (rest.isBlank()) prev else prev + glue(prev, rest) + rest
            }
            if (nc.length >= MIN_NORM_INSIDE && np.contains(nc)) return prev
            val nlcp = commonPrefixLen(nc, np)
            if (nlcp >= minOf(np.length, 60) && nlcp >= np.length * 0.6) return cont.trim()
        }

        val lead = cont.trimStart()
        val lcp = commonPrefixLen(lead, prevTrim)
        if (prevTrim.length >= 40 && lcp >= minOf(prevTrim.length, 60) && lcp >= prevTrim.length * 0.6) {
            return when {
                lead.length <= prevTrim.length -> prev
                lcp == prevTrim.length -> prevTrim + lead.substring(prevTrim.length)
                else -> lead
            }
        }

        // The reply was cut inside a code block: drop the fence the continuation reopens
        if (countFences(prev) % 2 == 1) {
            val m = Regex("^\\s*```[A-Za-z0-9_+.#-]*[ \\t]*\\r?\\n").find(cont)
            if (m != null) cont = cont.substring(m.range.last + 1)
        }

        cont = dropOverlap(prevTrim, cont)

        val lastLine = prevTrim.substringAfterLast('\n').trim()
        if (lastLine.length >= 12) {
            val l = cont.trimStart()
            if (l.startsWith(lastLine)) cont = l.substring(lastLine.length)
        }

        if (cont.isBlank()) return prev
        return prev + glue(prev, cont) + cont
    }

    // Adds a space only where neither side already provides a boundary
    private fun glue(prev: String, cont: String): String {
        if (prev.isEmpty() || cont.isEmpty()) return ""
        if (prev.last().isWhitespace() || cont.first().isWhitespace()) return ""
        if (cont.first() in ".,;:!?)]}%»”…") return ""
        if (prev.last() in "([{«“\"'/-") return ""
        return " "
    }

    private fun dropOverlap(prevTrim: String, cont: String): String {
        val np = normalize(prevTrim)
        val nc = normalize(cont)
        var k = minOf(np.length, nc.length, MAX_OVERLAP)
        while (k >= MIN_OVERLAP) {
            if (np.endsWith(nc.substring(0, k))) return cont.substring(consumeNormalized(cont, k))
            k--
        }
        return cont
    }

    private fun isNoise(c: Char): Boolean = c.isWhitespace() || c == '*' || c == '_'

    private fun normalize(t: String): String {
        val sb = StringBuilder(t.length)
        for (c in t) if (!isNoise(c)) sb.append(c)
        return sb.toString()
    }

    private fun consumeNormalized(t: String, n: Int): Int {
        var seen = 0
        var i = 0
        while (i < t.length && seen < n) {
            if (!isNoise(t[i])) seen++
            i++
        }
        while (i < t.length && isNoise(t[i])) i++
        return i
    }

    private fun commonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun countFences(t: String): Int {
        var n = 0
        var i = t.indexOf("```")
        while (i >= 0) { n++; i = t.indexOf("```", i + 3) }
        return n
    }
}
