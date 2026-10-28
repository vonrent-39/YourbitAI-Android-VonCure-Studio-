package com.youraicqa.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinModelsParseTest {

    @Test
    fun `valid response is parsed with accent and flags`() {
        val r = BuiltinModels.parse("""
            {"rev":7,"updatedAt":"x","useBuiltin":false,"models":[
              {"name":"A","provider":"groq","id":"openai/gpt-oss-120b","badge":"120","desc":"d","recommended":true,"accent":"#FFB74D","isImage":false},
              {"name":"F","provider":"cloudflare","id":"@cf/black-forest-labs/flux-1-schnell","accent":"#AB47BC","isImage":true}
            ]}
        """.trimIndent())!!
        assertEquals(7L, r.rev)
        assertFalse(r.useBuiltin)
        assertEquals(2, r.models.size)
        assertEquals(0xFFFFB74D.toInt(), r.models[0].accent)
        assertTrue(r.models[0].recommended)
        assertTrue(r.models[1].isImage)
    }

    @Test
    fun `unknown provider, bad id and duplicates are dropped`() {
        val r = BuiltinModels.parse("""
            {"rev":1,"models":[
              {"name":"X","provider":"evil","id":"x"},
              {"name":"Y","provider":"groq","id":"bad id with spaces"},
              {"name":"Z","provider":"groq","id":""},
              {"name":"A","provider":"groq","id":"m1"},
              {"name":"A2","provider":"groq","id":"m1"}
            ]}
        """.trimIndent())!!
        assertEquals(listOf("m1"), r.models.map { it.id })
    }

    @Test
    fun `unchanged and maintenance responses`() {
        val u = BuiltinModels.parse("""{"unchanged":true,"rev":4}""")!!
        assertTrue(u.unchanged)
        val m = BuiltinModels.parse("""{"rev":5,"useBuiltin":true,"models":[]}""")!!
        assertTrue(m.useBuiltin)
        assertTrue(m.models.isEmpty())
    }

    @Test
    fun `garbage is rejected so the app keeps its current list`() {
        assertNull(BuiltinModels.parse("<html>502 Bad Gateway</html>"))
        assertNull(BuiltinModels.parse("""{"rev":1}"""))
        assertNull(BuiltinModels.parse(""))
    }

    @Test
    fun `bad accent falls back to default and name falls back to id`() {
        val r = BuiltinModels.parse("""{"rev":1,"models":[{"provider":"gemini","id":"g1","accent":"red"}]}""")!!
        assertEquals(0xFF8AB4F8.toInt(), r.models[0].accent)
        assertEquals("g1", r.models[0].name)
    }

    @Test
    fun `new providers are accepted`() {
        val r = BuiltinModels.parse("""
            {"rev":2,"models":[
              {"name":"L","provider":"llm7","id":"gpt-4.1-nano-2025-04-14"},
              {"name":"P","provider":"pollinations","id":"openai"},
              {"name":"M","provider":"MISTRAL","id":"mistral-small-latest"},
              {"name":"C","provider":"cohere","id":"command-a-03-2025"}
            ]}
        """.trimIndent())!!
        assertEquals(listOf("llm7", "pollinations", "mistral", "cohere"), r.models.map { it.provider })
    }

    @Test
    fun `file json keeps server fields and adds fetchedAt`() {
        val server = """{"rev":9,"updatedAt":"x","useBuiltin":false,"models":[{"name":"A","provider":"groq","id":"m1"}]}"""
        val file = BuiltinModels.toFileJson(server, 1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, BuiltinModels.fetchedAtOf(file))
        val r = BuiltinModels.parse(file)!!
        assertEquals(9L, r.rev)
        assertEquals(listOf("m1"), r.models.map { it.id })
    }

    @Test
    fun `new update replaces the stored json`() {
        val first = BuiltinModels.toFileJson("""{"rev":1,"models":[{"provider":"groq","id":"old"}]}""", 1L)
        val second = BuiltinModels.toFileJson("""{"rev":2,"models":[{"provider":"mistral","id":"new"}]}""", 2L)
        assertEquals(listOf("new"), BuiltinModels.parse(second)!!.models.map { it.id })
        assertEquals(2L, BuiltinModels.fetchedAtOf(second))
        assertEquals(listOf("old"), BuiltinModels.parse(first)!!.models.map { it.id })
    }

    @Test
    fun `file without timestamp is treated as expired`() {
        assertEquals(0L, BuiltinModels.fetchedAtOf("""{"rev":1,"models":[]}"""))
        assertEquals(0L, BuiltinModels.fetchedAtOf("not json"))
    }

    @Test
    fun `mode and hide are parsed, replace is the default`() {
        val r = BuiltinModels.parse("""{"rev":3,"mode":"merge","models":[{"provider":"mistral","id":"m1"}],"hide":["groq/old","GROQ/Old2"," "]}""")!!
        assertEquals("merge", r.mode)
        assertEquals(setOf("groq/old", "groq/old2"), r.hide)
        assertEquals("replace", BuiltinModels.parse("""{"rev":1,"models":[]}""")!!.mode)
        assertEquals("replace", BuiltinModels.parse("""{"rev":1,"mode":"weird","models":[]}""")!!.mode)
    }

    @Test
    fun `merge keeps base order, overrides in place, appends new, hides`() {
        val base = listOf("groq/a", "groq/b", "gemini/c", "gemini/d")
        val overlay = listOf("groq/b*", "mistral/x", "cohere/y")
        val key = { s: String -> s.removeSuffix("*") }
        val out = BuiltinModels.mergeByKey(base, overlay, setOf("gemini/d"), key = key)
        assertEquals(listOf("groq/a", "groq/b*", "gemini/c", "mistral/x", "cohere/y"), out)
    }

    @Test
    fun `merge with empty overlay only hides`() {
        val out = BuiltinModels.mergeByKey(listOf("a/1", "a/2"), emptyList(), setOf("a/1")) { it }
        assertEquals(listOf("a/2"), out)
    }

    @Test
    fun `descLoc and order are parsed`() {
        val r = BuiltinModels.parse("""
            {"rev":4,"mode":"merge","order":["Mistral/X"," ","groq/a"],"models":[
              {"provider":"mistral","id":"x","name":"X","descLoc":{"en":"English","uk":"Укр","xx":"ignored","es":"  "}}
            ]}
        """.trimIndent())!!
        assertEquals(listOf("mistral/x", "groq/a"), r.order)
        assertEquals(mapOf("en" to "English", "uk" to "Укр"), r.models[0].descLoc)
    }

    @Test
    fun `applyOrder puts listed first and keeps the rest stable`() {
        val out = BuiltinModels.applyOrder(listOf("a", "b", "c", "d"), listOf("c", "zzz", "a")) { it }
        assertEquals(listOf("c", "a", "b", "d"), out)
        assertEquals(listOf("a", "b"), BuiltinModels.applyOrder(listOf("a", "b"), emptyList()) { it })
    }

    @Test
    fun `replacement takes the place of the bundled model`() {
        val base = listOf("groq/a", "groq/b", "gemini/c")
        val overlay = listOf("mistral/x", "cohere/y")
        val repl = mapOf("mistral/x" to "groq/b")
        val out = BuiltinModels.mergeByKey(base, overlay, setOf("gemini/c"), replacesOf = { repl[it] }, key = { it })
        assertEquals(listOf("groq/a", "mistral/x", "cohere/y"), out)
    }

    @Test
    fun `replaces field is parsed in lower case`() {
        val r = BuiltinModels.parse("""{"rev":1,"mode":"merge","models":[{"provider":"mistral","id":"x","replaces":"Groq/OpenAI/GPT-OSS-120B"}]}""")!!
        assertEquals("groq/openai/gpt-oss-120b", r.models[0].replaces)
    }

    @Test
    fun `video flag and poll hint are parsed`() {
        val r = BuiltinModels.parse("""
            {"rev":3,"pollMs":15000,"models":[
              {"name":"V","provider":"gemini","id":"g-video","isVideo":true},
              {"name":"T","provider":"groq","id":"t1"}
            ]}
        """.trimIndent())!!
        assertEquals(15000L, r.pollMs)
        assertTrue(r.models[0].isVideo)
        assertFalse(r.models[1].isVideo)
        val u = BuiltinModels.parse("""{"unchanged":true,"rev":3,"pollMs":20000}""")!!
        assertEquals(20000L, u.pollMs)
    }

    @Test
    fun `flags and sections are parsed`() {
        val r = BuiltinModels.parse("""
            {"rev":4,"models":[
              {"name":"P","provider":"openai","id":"gpt-5","section":"paid","isVision":true,"multimodal":true,"unverified":true},
              {"name":"X","provider":"deepseek","id":"d1","section":"bogus"}
            ]}
        """.trimIndent())!!
        assertEquals("paid", r.models[0].section)
        assertTrue(r.models[0].isVision)
        assertTrue(r.models[0].multimodal)
        assertTrue(r.models[0].unverified)
        assertEquals("", r.models[1].section)
    }

    @Test
    fun `needBundled is parsed in full and unchanged responses`() {
        assertTrue(BuiltinModels.parse("""{"unchanged":true,"rev":4,"needBundled":true}""")!!.needBundled)
        assertTrue(BuiltinModels.parse("""{"rev":4,"models":[],"needBundled":true}""")!!.needBundled)
        assertFalse(BuiltinModels.parse("""{"unchanged":true,"rev":4}""")!!.needBundled)
        assertFalse(BuiltinModels.parse("""{"rev":4,"models":[]}""")!!.needBundled)
    }

    @Test
    fun `bundled list survives its own server validation`() {
        val r = BuiltinModels.parse("""{"rev":1,"models":${BuiltinModels.bundledJson()}}""")!!
        assertEquals(BuiltinModels.bundled.size, r.models.size)
        val keys = BuiltinModels.bundled.map { "${it.provider}/${it.id}".lowercase() }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `bundled json carries capability flags`() {
        val r = BuiltinModels.parse("""{"rev":1,"models":${BuiltinModels.bundledJson()}}""")!!
        val vl = r.models.first { it.id == "nvidia/nemotron-3.5-super-vl:free" }
        assertTrue(vl.isVideo)
        assertTrue(vl.isVision)
        assertTrue(vl.multimodal)
        val lightning = r.models.first { it.id == "nvidia/nemotron-3.5-lightning:free" }
        assertFalse(lightning.isVideo)
        assertFalse(lightning.multimodal)
        val clef = r.models.first { it.id == "@cf/cloudflare/clef-omni" }
        assertEquals("experimental", clef.section)
        assertTrue(clef.unverified)
        assertFalse(clef.isVision)
    }

    @Test
    fun `nemotron super vl is a video model in the app list`() {
        val m = BuiltinModels.bundled.first { it.id == "nvidia/nemotron-3.5-super-vl:free" }
        assertTrue(m.isVideoVision)
        assertTrue(m.isMultimodal)
    }

    @Test
    fun `system one models are detected`() {
        assertTrue(BuiltinModels.isSystemOne("cloudflare", "@cf/cloudflare/clef"))
        assertTrue(BuiltinModels.isSystemOne("cloudflare", "@cf/cloudflare/clef-omni"))
        assertTrue(BuiltinModels.isSystemOne("cloudflare", "@cf/cloudflare/clairic"))
        assertFalse(BuiltinModels.isSystemOne("cloudflare", "@cf/meta/llama-3.3-70b-instruct-fp8-fast"))
        assertFalse(BuiltinModels.isSystemOne("openrouter", "@cf/cloudflare/clef"))
    }

    @Test
    fun `bundled hash is stable and changes with the list`() {
        val a = BuiltinModels.bundledHash()
        assertEquals(a, BuiltinModels.bundledHash(BuiltinModels.bundledJson()))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(a))
        assertTrue(a != BuiltinModels.bundledHash(BuiltinModels.bundledJson(BuiltinModels.bundled.drop(1))))
    }
}
