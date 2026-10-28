package com.youraicqa.app.linkimporter

object FandomHosts {
    private val known = mapOf(
        "гарри поттер" to "harrypotter", "harry potter" to "harrypotter",
        "звёздные войны" to "starwars", "star wars" to "starwars",
        "марвел" to "marvel", "marvel" to "marvel", "мстители" to "marvel",
        "ведьмак" to "witcher", "witcher" to "witcher",
        "властелин колец" to "lotr", "lord of the rings" to "lotr", "толкин" to "lotr",
        "игра престолов" to "gameofthrones", "game of thrones" to "gameofthrones",
        "покемон" to "pokemon", "pokemon" to "pokemon",
        "наруто" to "naruto", "naruto" to "naruto",
        "майнкрафт" to "minecraft", "minecraft" to "minecraft",
        "звёздный путь" to "startrek", "star trek" to "startrek",
        "аватар" to "avatar", "avatar" to "avatar",
        "рик и морти" to "rickandmorty", "rick and morty" to "rickandmorty",
        "дэлтарун" to "deltarune", "deltarune" to "deltarune",
        "андертейл" to "undertale", "undertale" to "undertale",
        "соник" to "sonic", "sonic" to "sonic",
        "скибиди" to "skibidi-toilet", "skibidi" to "skibidi-toilet"
    )

    fun guess(query: String): String? {
        val q = query.lowercase()
        for ((k, host) in known) if (q.contains(k)) return host
        return null
    }

    fun resolve(query: String): String? {
        if (query.isBlank()) return null

        guess(query)?.let { return it }

        val encoded = try { java.net.URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { return null }
        val hosts = linkedSetOf<String>()

        fun collect(text: String) {
            Regex("""https?://([a-z0-9-]+)\.fandom\.com(?:/wiki/|/f/|/page/|/f$)""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.groupValues[1].lowercase() }
                .filter { it !in setOf("www", "community", "dev", "support", "help", "static") }
                .forEach { hosts.add(it) }

            Regex("""//([a-z0-9-]+)\.fandom\.com/wiki/""", RegexOption.IGNORE_CASE)
                .findAll(text)
                .map { it.groupValues[1].lowercase() }
                .filter { it !in setOf("www", "community", "dev", "support", "help", "static") }
                .forEach { hosts.add(it) }
        }

        // Fandom's global search supports cross-wiki scopes, so no hard-coded franchise list is needed.
        val discoveryUrls = listOf(
            "https://community.fandom.com/wiki/Special:Search?query=$encoded&scope=external",
            "https://community.fandom.com/wiki/Special:Search?query=$encoded&scope=cross-wiki",
            "https://www.fandom.com/search?query=$encoded"
        )
        for (url in discoveryUrls) {
            if (hosts.isNotEmpty()) break
            try {
                val (code, html) = httpGet(
                    url,
                    connectTimeoutMs = 5000,
                    readTimeoutMs = 8000
                )
                if (code in 200..299) collect(html)
            } catch (_: Exception) {}
        }

        // Search engines are only a fallback for niche or new wikis missing from Fandom's global search.
        if (hosts.isEmpty()) {
            try {
                val q = java.net.URLEncoder.encode("site:fandom.com/wiki $query", "UTF-8")
                val (code, html) = httpGet(
                    "https://html.duckduckgo.com/html/?q=$q",
                    connectTimeoutMs = 5000,
                    readTimeoutMs = 8000
                )
                if (code in 200..299) collect(html)
            } catch (_: Exception) {}
        }

        return hosts.firstOrNull()
    }

    private fun httpGet(
        urlStr: String,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 12000,
        userAgent: String = "Yourbit AI-Android/1.0"
    ): Pair<Int, String> {
        val c = java.net.URI(urlStr).toURL().openConnection() as java.net.HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", userAgent)
        return try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to text
        } finally {
            c.disconnect()
        }
    }
}
