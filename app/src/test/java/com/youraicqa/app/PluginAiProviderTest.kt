package com.youraicqa.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginAiProviderTest {

    private fun provider(json: String) =
        PluginAiLogic.parseProvider("my-plugin", "My Plugin", JSONObject(json))!!

    @Test
    fun `provider needs a valid id`() {
        assertNull(PluginAiLogic.parseProvider("p", "P", JSONObject("""{"name":"x"}""")))
        assertNull(PluginAiLogic.parseProvider("p", "P", JSONObject("""{"id":"bad id!"}""")))
        assertNull(PluginAiLogic.parseProvider("p", "P", JSONObject("""{"id":"${"a".repeat(65)}"}""")))
        assertNotNull(PluginAiLogic.parseProvider("p", "P", JSONObject("""{"id":"my.llm_v-2"}""")))
    }

    @Test
    fun `provider key combines plugin and provider ids and name falls back to id`() {
        val p = provider("""{"id":"llm"}""")
        assertEquals("my-plugin/llm", p.key)
        assertEquals("llm", p.name)
        assertEquals("My Plugin", p.pluginName)
    }

    @Test
    fun `timeout is clamped to a sane range`() {
        assertEquals(PluginAiLogic.DEFAULT_TIMEOUT_MS, provider("""{"id":"a"}""").timeoutMs)
        assertEquals(PluginAiLogic.MIN_TIMEOUT_MS, provider("""{"id":"a","timeoutMs":10}""").timeoutMs)
        assertEquals(PluginAiLogic.MAX_TIMEOUT_MS, provider("""{"id":"a","timeoutMs":99999999}""").timeoutMs)
        assertEquals(30_000L, provider("""{"id":"a","timeoutMs":30000}""").timeoutMs)
    }

    @Test
    fun `models accept objects and plain strings, drop empty and duplicate ids`() {
        val models = PluginAiLogic.parseModels(
            JSONArray("""[{"id":"a","name":"Model A","description":"d"},"b",{"id":""},{"id":"a","name":"dup"},42,{"id":"c"}]""")
        )
        assertEquals(listOf("a", "b", "c"), models.map { it.id })
        assertEquals("Model A", models[0].name)
        assertEquals("d", models[0].description)
        assertEquals("b", models[1].name)
        assertEquals("c", models[2].name)
    }

    @Test
    fun `null models array gives empty list and the list is capped`() {
        assertTrue(PluginAiLogic.parseModels(null).isEmpty())
        val big = JSONArray().also { arr -> repeat(PluginAiLogic.MAX_MODELS + 50) { arr.put("m$it") } }
        assertEquals(PluginAiLogic.MAX_MODELS, PluginAiLogic.parseModels(big).size)
    }

    @Test
    fun `provider json round trip keeps models settings and timeout`() {
        val original = provider(
            """{"id":"llm","name":"LLM","description":"desc","timeoutMs":45000,
                "models":[{"id":"m1","name":"One"},{"id":"m2"}],
                "settings":{"baseUrl":{"default":"https://api.example.com/v1","required":true},"apiKey":{"required":false}}}"""
        )
        val back = PluginAiLogic.parseProvider("my-plugin", "My Plugin", PluginAiLogic.providerToJson(original))
        assertEquals(original, back)
    }

    @Test
    fun `settings fields exist only when the provider declares them`() {
        val none = provider("""{"id":"a"}""")
        assertNull(none.baseUrl); assertNull(none.apiKey)
        val both = provider("""{"id":"a","settings":{"baseUrl":true,"apiKey":{"required":true}}}""")
        assertNotNull(both.baseUrl); assertFalse(both.baseUrl!!.required)
        assertTrue(both.apiKey!!.required)
        val off = provider("""{"id":"a","settings":{"baseUrl":false,"apiKey":null}}""")
        assertNull(off.baseUrl); assertNull(off.apiKey)
    }

    @Test
    fun `normalizeBaseUrl trims trailing slash and rejects non http urls`() {
        assertEquals("", PluginAiLogic.normalizeBaseUrl("   "))
        assertEquals("https://api.example.com/v1", PluginAiLogic.normalizeBaseUrl(" https://api.example.com/v1/ "))
        assertEquals("http://example.com", PluginAiLogic.normalizeBaseUrl("http://example.com/"))
        assertNull(PluginAiLogic.normalizeBaseUrl("ftp://example.com"))
        assertNull(PluginAiLogic.normalizeBaseUrl("javascript:alert(1)"))
        assertNull(PluginAiLogic.normalizeBaseUrl("not a url"))
        assertNull(PluginAiLogic.normalizeBaseUrl("file:///etc/passwd"))
    }

    @Test
    fun `effective settings use the plugin default address and ignore undeclared fields`() {
        val p = provider("""{"id":"a","settings":{"baseUrl":{"default":"https://def.example.com"}}}""")
        val e = PluginAiLogic.effectiveSettings(p, PluginAiSettingsValue("", "secret"))
        assertEquals("https://def.example.com", e.baseUrl)
        assertEquals("", e.apiKey)
        val own = PluginAiLogic.effectiveSettings(p, PluginAiSettingsValue("https://mine.example.com", ""))
        assertEquals("https://mine.example.com", own.baseUrl)
    }

    @Test
    fun `missing required settings produce errors without leaking values`() {
        val p = provider("""{"id":"a","name":"A","settings":{"baseUrl":{"required":true},"apiKey":{"required":true}}}""")
        val noUrl = PluginAiLogic.missingSettingError(p, PluginAiSettingsValue("", "k"))
        assertNotNull(noUrl)
        assertEquals(6, AppErrorUi.classify(noUrl!!.display()).code)
        val noKey = PluginAiLogic.missingSettingError(p, PluginAiSettingsValue("https://x.example.com", ""))
        assertNotNull(noKey)
        assertEquals(22, AppErrorUi.classify(noKey!!.display()).code)
        assertNull(PluginAiLogic.missingSettingError(p, PluginAiSettingsValue("https://x.example.com", "k")))
        val optional = provider("""{"id":"a","settings":{"apiKey":{"required":false}}}""")
        assertNull(PluginAiLogic.missingSettingError(optional, PluginAiSettingsValue("", "")))
    }

    @Test
    fun `request splits system prompt from history and carries params and settings`() {
        val p = provider("""{"id":"llm"}""")
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Base prompt"))
            .put(JSONObject().put("role", "system").put("content", "Search context"))
            .put(JSONObject().put("role", "user").put("content", "Hi"))
            .put(JSONObject().put("role", "assistant").put("content", "Hello"))
            .put(JSONObject().put("role", "user").put("content", "Bye"))
        val req = PluginAiLogic.buildRequest(
            p, PluginAiModel("m1", "Model 1"), msgs, 2800, 0.7,
            PluginAiSettingsValue("https://api.example.com", "k-123")
        )
        assertEquals("llm", req.getString("providerId"))
        assertEquals("m1", req.getJSONObject("model").getString("id"))
        assertEquals("Base prompt\n\nSearch context", req.getString("systemPrompt"))
        val history = req.getJSONArray("messages")
        assertEquals(3, history.length())
        assertEquals("user", history.getJSONObject(0).getString("role"))
        assertEquals("Hello", history.getJSONObject(1).getString("content"))
        assertEquals(2800, req.getJSONObject("params").getInt("maxTokens"))
        assertEquals(0.7, req.getJSONObject("params").getDouble("temperature"), 1e-9)
        assertEquals("https://api.example.com", req.getJSONObject("settings").getString("baseUrl"))
        assertEquals("k-123", req.getJSONObject("settings").getString("apiKey"))
    }

    @Test
    fun `content parts are flattened to text and images are skipped`() {
        val parts = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "look"))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:...")))
            .put(JSONObject().put("type", "text").put("text", "here"))
        assertEquals("look\nhere", PluginAiLogic.contentText(parts))
        assertEquals("", PluginAiLogic.contentText(null))
        assertEquals("", PluginAiLogic.contentText(JSONObject.NULL))
        assertEquals("plain", PluginAiLogic.contentText("plain"))
    }

    @Test
    fun `no system prompt gives an empty string`() {
        val req = PluginAiLogic.buildRequest(
            provider("""{"id":"a"}"""), PluginAiModel("m", "m"),
            JSONArray().put(JSONObject().put("role", "user").put("content", "x")), 100, 0.7, PluginAiSettingsValue()
        )
        assertEquals("", req.getString("systemPrompt"))
    }

    @Test
    fun `result accepts string text content or reply and rejects empty`() {
        assertEquals("ok", PluginAiLogic.parseResult("ok"))
        assertEquals("a", PluginAiLogic.parseResult(JSONObject("""{"text":"a"}""")))
        assertEquals("b", PluginAiLogic.parseResult(JSONObject("""{"content":"b"}""")))
        assertEquals("c", PluginAiLogic.parseResult(JSONObject("""{"text":"","reply":"c"}""")))
        assertNull(PluginAiLogic.parseResult("   "))
        assertNull(PluginAiLogic.parseResult(null))
        assertNull(PluginAiLogic.parseResult(JSONObject.NULL))
        assertNull(PluginAiLogic.parseResult(JSONObject("""{"other":"x"}""")))
        assertNull(PluginAiLogic.parseResult(42))
    }

    @Test
    fun `explicit kinds map to the server error catalogue`() {
        assertEquals(ServerErrors.E05, PluginAiLogic.mapError("P", "timeout", "", 0).code)
        assertEquals(ServerErrors.E06, PluginAiLogic.mapError("P", "connection", "", 0).code)
        assertEquals(ServerErrors.E01, PluginAiLogic.mapError("P", "unauthorized", "", 0).code)
        assertEquals(ServerErrors.E09, PluginAiLogic.mapError("P", "rate_limited", "", 0).code)
        assertEquals(ServerErrors.E03, PluginAiLogic.mapError("P", "not_found", "", 0).code)
        assertEquals(ServerErrors.E23, PluginAiLogic.mapError("P", "content_filter", "", 0).code)
        assertEquals(ServerErrors.E01, PluginAiLogic.mapError("P", "http", "denied", 401).code)
        assertEquals(ServerErrors.E09, PluginAiLogic.mapError("P", "http", "", 429).code)
    }

    @Test
    fun `nano net failures are recognised by their reason`() {
        assertEquals(ServerErrors.E05, PluginAiLogic.mapError("P", "", "request_failed: Read timed out", 0).code)
        assertEquals(ServerErrors.E06, PluginAiLogic.mapError("P", "", "dns_resolution_failed", 0).code)
        assertEquals(ServerErrors.E06, PluginAiLogic.mapError("P", "", "request_failed: Connection refused", 0).code)
        val blocked = PluginAiLogic.mapError("P", "", "blocked_address", 0)
        assertNull(blocked.code)
        assertTrue(blocked.message.contains("локальному"))
        assertEquals(6, AppErrorUi.classify(PluginAiLogic.mapError("P", "", "invalid_url", 0).display()).code)
    }

    @Test
    fun `unknown plugin errors keep the text, are truncated and classified as plugin errors`() {
        val e = PluginAiLogic.mapError("Cool plugin", "", "model exploded", 0)
        assertNull(e.code)
        assertTrue(e.message.contains("Cool plugin"))
        assertTrue(e.message.contains("model exploded"))
        assertEquals(8, AppErrorUi.classify(e.display()).code)
        val long = PluginAiLogic.mapError("P", "", "x".repeat(2000), 0)
        assertTrue(long.message.length < 400)
        assertTrue(PluginAiLogic.mapError("P", "", "", 0).message.contains("неизвестная ошибка"))
    }

    @Test
    fun `api key never appears in error text`() {
        val key = "sk-very-secret-key"
        val e = PluginAiLogic.mapError("P", "", "bad auth for $key on server", 0, listOf(key))
        assertFalse(e.display().contains(key))
        assertTrue(e.display().contains("***"))
        assertEquals("a ***", PluginAiLogic.redact("a abcd", listOf("abcd")))
        assertEquals("abc", PluginAiLogic.redact("abc", listOf("abc")))
    }

    @Test
    fun `lifecycle errors read well and are not catalogued as timeouts`() {
        assertTrue(PluginAiLogic.pluginStopped("P").message.contains("«P»"))
        assertTrue(PluginAiLogic.pluginNotReady("P").message.contains("«P»"))
        assertTrue(PluginAiLogic.providerGone("Model").message.contains("Model"))
        assertEquals(8, AppErrorUi.classify(PluginAiLogic.pluginStopped("P").display()).code)
    }

    @Test
    fun `aiProvider capability implies AI_PROVIDER and NETWORK`() {
        val caps = PluginCapabilities.parse(JSONObject("""{"aiProvider":true}"""))
        assertTrue(caps.aiProvider)
        assertEquals(setOf(PluginPermission.AI_PROVIDER, PluginPermission.NETWORK), caps.impliedPermissions())
        assertFalse(PluginCapabilities.parse(JSONObject("""{"files":true}""")).aiProvider)
    }

    @Test
    fun `AI_PROVIDER can be declared in the flat permissions list and in a plugin manifest`() {
        assertEquals(
            setOf(PluginPermission.AI_PROVIDER),
            PluginPermission.parseSet(JSONArray("""["ai.provider"]"""))
        )
        val plugin = PluginStore.parse(
            """{"id":"x","permissions":["AI_PROVIDER","NETWORK"],"script":"nano.ai.registerProvider({})"}"""
        )!!
        assertTrue(PluginPermission.AI_PROVIDER in plugin.permissions)
        val viaCaps = PluginStore.parse("""{"id":"y","capabilities":{"aiProvider":true},"script":"1"}""")!!
        assertTrue(PluginPermission.AI_PROVIDER in viaCaps.permissions)
        assertTrue(PluginPermission.NETWORK in viaCaps.permissions)
    }
}
