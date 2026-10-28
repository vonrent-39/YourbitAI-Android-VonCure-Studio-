package com.youraicqa.app

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerLogicTest {

    private fun ok(raw: String): String {
        val r = ServerLogic.normalizeBaseUrl(raw)
        assertTrue("ожидали Ok для '$raw', получили $r", r is ServerLogic.UrlResult.Ok)
        return (r as ServerLogic.UrlResult.Ok).url
    }

    private fun err(raw: String): String {
        val r = ServerLogic.normalizeBaseUrl(raw)
        assertTrue("ожидали Error для '$raw', получили $r", r is ServerLogic.UrlResult.Error)
        return (r as ServerLogic.UrlResult.Error).message
    }

    @Test
    fun `http is rejected with a clear message`() {
        assertTrue(err("http://example.com").contains("https://"))
        assertTrue(err("HTTP://example.com/v1").contains("http://"))
    }

    @Test
    fun `scheme is not guessed`() {
        assertTrue(err("example.com").contains("https://"))
        assertTrue(err("example.com/v1").contains("https://"))
        assertTrue(err("ftp://example.com").contains("https://"))
    }

    @Test
    fun `empty input is rejected`() {
        err("")
        err("   ")
        err("https://")
    }

    @Test
    fun `bare host gets v1`() {
        assertEquals("https://example.com/v1", ok("https://example.com"))
        assertEquals("https://example.com/v1", ok("https://example.com/"))
        assertEquals("https://example.com:8443/v1", ok("https://example.com:8443"))
    }

    @Test
    fun `pasted endpoint tails are stripped`() {
        assertEquals("https://example.com/v1", ok("https://example.com/v1/chat/completions"))
        assertEquals("https://example.com/v1", ok("https://example.com/v1/models"))
        assertEquals("https://example.com/v1", ok("https://example.com/v1/chat/completions/"))
        assertEquals("https://example.com/openai/v1", ok("https://example.com/openai/v1/models"))
    }

    @Test
    fun `tail on a bare host leaves v1`() {
        assertEquals("https://example.com/v1", ok("https://example.com/chat/completions"))
        assertEquals("https://example.com/v1", ok("https://example.com/models"))
    }

    @Test
    fun `spaces and trailing slashes are removed`() {
        assertEquals("https://example.com/v1", ok("  https://example.com/v1/  "))
        assertEquals("https://example.com/v1", ok("https://example.com/v1///"))
        assertEquals("https://example.com/v1", ok("https://exam ple.com/v1\n"))
    }

    @Test
    fun `addresses with a path are not changed`() {
        assertEquals("https://example.com/v1", ok("https://example.com/v1"))
        assertEquals("https://example.com/openai/v1", ok("https://example.com/openai/v1"))
        assertEquals("https://example.com/api/v2", ok("https://example.com/api/v2"))
    }

    @Test
    fun `host is lowercased but path case is kept`() {
        assertEquals("https://example.com/Api/V1", ok("https://EXAMPLE.com/Api/V1"))
    }

    @Test
    fun `credentials query and bad hosts are rejected`() {
        err("https://user:pass@example.com")
        err("https://example.com/v1?key=1")
        err("https://example.com/v1#x")
        err("https://exa_mple!.com")
        err("https://example.com:99999")
    }

    @Test
    fun `host extraction`() {
        assertEquals("example.com", ServerLogic.hostOf("https://Example.com:8443/v1"))
        assertTrue(ServerLogic.sameHost("https://a.com/v1", "https://A.com:444/x"))
        assertFalse(ServerLogic.sameHost("https://a.com/v1", "https://b.com/v1"))
    }

    private fun sse(vararg lines: String, onDelta: (String) -> Unit = {}) =
        ServerLogic.parseSse(lines.asSequence(), onDelta)

    private fun chunk(content: String) = """data: {"choices":[{"delta":{"content":"$content"}}]}"""

    @Test
    fun `normal stream`() {
        val deltas = mutableListOf<String>()
        val r = sse(chunk("Hel"), "", chunk("lo"), "", "data: [DONE]", onDelta = { deltas += it })
        assertEquals("Hello", r.text)
        assertTrue(r.done)
        assertEquals(listOf("Hel", "lo"), deltas)
    }

    @Test
    fun `comments blank lines and other fields are ignored`() {
        val r = sse(": keep-alive", "", "event: message", "id: 7", "retry: 100", chunk("A"), ":ping", chunk("B"), "data: [DONE]")
        assertEquals("AB", r.text)
        assertTrue(r.done)
    }

    @Test
    fun `done stops reading`() {
        val r = sse(chunk("A"), "data: [DONE]", chunk("ignored"))
        assertEquals("A", r.text)
        assertTrue(r.done)
    }

    @Test
    fun `stream without done is not finished`() {
        val r = sse(chunk("par"), chunk("tial"))
        assertEquals("partial", r.text)
        assertFalse(r.done)
    }

    @Test
    fun `chunks without content are skipped`() {
        val r = sse(
            """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            """data: {"choices":[{"delta":{"content":null}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """data: {"usage":{"total_tokens":5}}""",
            "data: not json at all",
            chunk("ok"),
            "data: [DONE]"
        )
        assertEquals("ok", r.text)
        assertTrue(r.done)
    }

    @Test
    fun `reasoning content is ignored`() {
        val r = sse(
            """data: {"choices":[{"delta":{"reasoning_content":"thinking..."}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"more","content":"answer"}}]}""",
            "data: [DONE]"
        )
        assertEquals("answer", r.text)
    }

    @Test
    fun `crlf line endings are tolerated`() {
        val r = sse(chunk("x") + "\r", "data: [DONE]\r")
        assertEquals("x", r.text)
        assertTrue(r.done)
    }

    @Test
    fun `data without space after colon`() {
        val r = sse("""data:{"choices":[{"delta":{"content":"z"}}]}""", "data:[DONE]")
        assertEquals("z", r.text)
        assertTrue(r.done)
    }

    private val jsonBody = """{"choices":[{"message":{"role":"assistant","content":"Привет"}}]}"""

    @Test
    fun `json instead of sse is handled as a normal reply`() {
        val got = mutableListOf<String>()
        val text = ServerLogic.readChatResponse("application/json", sequenceOf(jsonBody)) { got += it }
        assertEquals("Привет", text)
        assertEquals(listOf("Привет"), got)
    }

    @Test
    fun `json is detected even with a wrong content type`() {
        assertEquals("Привет", ServerLogic.readChatResponse("text/plain", sequenceOf("", jsonBody)))
        assertEquals("Привет", ServerLogic.readChatResponse(null, sequenceOf("{\"choices\":[{\"message\":", "{\"content\":\"Привет\"}}]}")))
    }

    @Test
    fun `sse by content type and by sniffing`() {
        val lines = listOf(chunk("a"), chunk("b"), "data: [DONE]")
        assertEquals("ab", ServerLogic.readChatResponse("text/event-stream; charset=utf-8", lines.asSequence()))
        assertEquals("ab", ServerLogic.readChatResponse(null, lines.asSequence()))
    }

    @Test
    fun `cut stream throws with partial text`() {
        try {
            ServerLogic.readChatResponse("text/event-stream", sequenceOf(chunk("abc"), chunk("def")))
            fail("ожидали StreamCutException")
        } catch (e: StreamCutException) {
            assertEquals("abcdef", e.partialText)
        }
    }

    @Test
    fun `empty body is a cut stream with empty text`() {
        try {
            ServerLogic.readChatResponse(null, emptySequence())
            fail()
        } catch (e: StreamCutException) {
            assertEquals("", e.partialText)
        }
    }

    @Test
    fun `json without text is an error`() {
        try {
            ServerLogic.parseChatJson("""{"choices":[{"message":{"content":""}}]}""")
            fail()
        } catch (e: ServerException) {
            assertNull(e.error.code)
        }
    }

    @Test
    fun `json error object with 200 is reported`() {
        try {
            ServerLogic.parseChatJson("""{"error":{"message":"Invalid model"}}""")
            fail()
        } catch (e: ServerException) {
            assertTrue(e.error.message.contains("Invalid model"))
        }
    }

    @Test
    fun `model ids are read from data`() {
        val ids = ServerLogic.parseModelIds("""{"object":"list","data":[{"id":"a"},{"id":" b "},{"id":"a"},{"x":1},{"id":""}]}""")
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `empty model list is not an error`() {
        assertEquals(emptyList<String>(), ServerLogic.parseModelIds("""{"data":[]}"""))
    }

    @Test
    fun `models response without data is an error`() {
        try { ServerLogic.parseModelIds("""{"hello":1}"""); fail() } catch (_: ServerException) {}
        try { ServerLogic.parseModelIds("<html>"); fail() } catch (_: ServerException) {}
    }

    @Test
    fun `failover is triggered by dns connect and connect timeout`() {
        assertTrue(ServerLogic.shouldFailover(ServerLogic.Phase.CONNECT, UnknownHostException("x")))
        assertTrue(ServerLogic.shouldFailover(ServerLogic.Phase.CONNECT, ConnectException("refused")))
        assertTrue(ServerLogic.shouldFailover(ServerLogic.Phase.CONNECT, SocketTimeoutException("connect timed out")))
    }

    @Test
    fun `failover is not triggered by read timeout tls or other errors`() {
        assertFalse(ServerLogic.shouldFailover(ServerLogic.Phase.RESPONSE, SocketTimeoutException("Read timed out")))
        assertFalse(ServerLogic.shouldFailover(ServerLogic.Phase.CONNECT, SSLHandshakeException("bad cert")))
        assertFalse(ServerLogic.shouldFailover(ServerLogic.Phase.CONNECT, CertChangedException("a", "b")))
        assertFalse(ServerLogic.shouldFailover(ServerLogic.Phase.RESPONSE, java.io.IOException("reset")))
        assertFalse(ServerLogic.shouldFailover(ServerLogic.Phase.RESPONSE, StreamCutException("x")))
    }

    @Test
    fun `failover http statuses`() {
        for (c in listOf(502, 503, 504)) assertTrue("$c", ServerLogic.shouldFailoverOnStatus(c))
        for (c in listOf(200, 400, 401, 403, 404, 408, 429, 500, 501, 505)) assertFalse("$c", ServerLogic.shouldFailoverOnStatus(c))
    }

    @Test
    fun `address order and memory`() {
        val p = "https://a.com/v1"; val f = "https://b.com/v1"
        assertEquals(listOf(p, f), ServerLogic.orderAddresses(p, f, null))
        assertEquals(listOf(f, p), ServerLogic.orderAddresses(p, f, f))
        assertEquals(listOf(p, f), ServerLogic.orderAddresses(p, f, p))
        assertEquals(listOf(p), ServerLogic.orderAddresses(p, "", f))
        assertEquals(listOf(p), ServerLogic.orderAddresses(p, p, null))

        var now = 1_000L
        val mem = ServerLogic.AddressMemory(ttlMs = 5 * 60_000L, now = { now })
        assertNull(mem.get("s"))
        mem.remember("s", f)
        assertEquals(f, mem.get("s"))
        now += 4 * 60_000L
        assertEquals(f, mem.get("s"))
        now += 2 * 60_000L
        assertNull(mem.get("s"))
        mem.remember("s", f); mem.forget("s")
        assertNull(mem.get("s"))
    }

    private fun server(id: String, vararg models: String) = CustomServer(id = id, name = "S$id", baseUrl = "https://$id.com/v1", models = models.toList())

    @Test
    fun `empty answer and error do not change the cached list`() {
        val cached = listOf("m1", "m2")
        assertEquals(cached, ServerLogic.applyRefresh(cached, Result.success(emptyList())))
        assertEquals(cached, ServerLogic.applyRefresh(cached, Result.failure(UnknownHostException("x"))))
        assertEquals(cached, ServerLogic.applyRefresh(cached, Result.failure(ServerException(ServerErrors.timeout()))))
    }

    @Test
    fun `non empty answer replaces the cache`() {
        assertEquals(listOf("n"), ServerLogic.applyRefresh(listOf("m1"), Result.success(listOf("n"))))
        assertEquals(listOf("n"), ServerLogic.applyRefresh(emptyList(), Result.success(listOf("n"))))
    }

    @Test
    fun `server models are virtual entries per server`() {
        val entries = ServerLogic.serverModelEntries(listOf(server("a", "x", "y", "x"), server("b", "x"), server("c")))
        assertEquals(
            listOf(
                ServerLogic.ServerModelEntry("a", "Sa", "x"),
                ServerLogic.ServerModelEntry("a", "Sa", "y"),
                ServerLogic.ServerModelEntry("b", "Sb", "x")
            ),
            entries
        )
    }

    @Test
    fun `assembling keeps user list intact and appends server models`() {
        val builtin = listOf("b1", "b2")
        val user = listOf("u1", "x")
        val userBefore = user.toList()
        val srv = listOf("x", "y")
        val all = ServerLogic.assembleModels(builtin, user, srv)
        assertEquals(listOf("b1", "b2", "u1", "x", "x", "y"), all)
        assertEquals(userBefore, user)
        assertEquals(emptyList<String>(), ServerLogic.assembleModels<String>(emptyList(), emptyList(), emptyList()))
        assertEquals(builtin + user, ServerLogic.assembleModels(builtin, user, emptyList()))
    }

    @Test
    fun `fingerprint helpers`() {
        val hex = ServerLogic.sha256Hex(byteArrayOf())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex)
        val shown = ServerLogic.formatFingerprint(hex)
        assertTrue(shown.startsWith("E3:B0:C4:42"))
        assertEquals(hex, ServerLogic.normalizeFingerprint(shown))
    }

    @Test
    fun `http statuses map to codes`() {
        assertEquals("E01", ServerErrors.fromHttp(401, "").code)
        assertEquals("E01", ServerErrors.fromHttp(403, "").code)
        assertEquals("E03", ServerErrors.fromHttp(404, "").code)
        assertEquals("E03", ServerErrors.fromHttp(400, """{"error":"The model 'x' does not exist"}""").code)
        assertEquals("E09", ServerErrors.fromHttp(429, "").code)
        assertEquals("E09", ServerErrors.fromHttp(500, "Rate limit exceeded").code)
        assertEquals("E23", ServerErrors.fromHttp(400, """{"error":{"code":"content_filter"}}""").code)
    }

    @Test
    fun `other errors are shown as is up to 300 chars`() {
        val e = ServerErrors.fromHttp(500, "x".repeat(1000))
        assertNull(e.code)
        assertEquals("HTTP 500: ".length + 300, e.message.length)
    }

    @Test
    fun `exceptions map to codes`() {
        assertEquals("E06", ServerErrors.fromThrowable(UnknownHostException("h")).code)
        assertEquals("E06", ServerErrors.fromThrowable(ConnectException("c"), online = true).code)
        assertEquals("E14", ServerErrors.fromThrowable(UnknownHostException("h"), online = false).code)
        assertEquals("E05", ServerErrors.fromThrowable(SocketTimeoutException("t")).code)
        assertEquals("E21", ServerErrors.fromThrowable(StreamCutException("p")).code)
        assertNull(ServerErrors.fromThrowable(SSLHandshakeException("x")).code)
        val wrapped = SSLHandshakeException("x").apply { initCause(CertChangedException("a", "b")) }
        assertTrue(ServerErrors.fromThrowable(wrapped).message.contains("Проверить"))
    }

    @Test
    fun `display contains the code line`() {
        assertEquals("Не удаётся подключиться к серверу. Проверь адрес и доступность сервера.\nКод ошибки: E06", ServerErrors.cannotConnect().display())
        assertSame(null, ServerErrors.certChanged().code)
    }

    @Test
    fun `managed flag is read from models`() {
        val r = ServerLogic.parseModels("""{"object":"list","data":[{"id":"a","managed":true},{"id":"b"},{"id":"c","managed":false}]}""")
        assertEquals(listOf("a", "b", "c"), r.ids)
        assertEquals(listOf("a"), r.managed)
    }

    @Test
    fun `replacement covers only models the server declared`() {
        val s1 = CustomServer(id = "1", name = "S1", baseUrl = "https://a.com/v1", models = listOf("x", "y"), managedModels = listOf("x"))
        val s2 = CustomServer(id = "2", name = "S2", baseUrl = "https://b.com/v1", models = listOf("x", "z"), managedModels = listOf("x", "z"))
        val map = ServerLogic.replacedBy(listOf(s1, s2))
        assertEquals(setOf("x", "z"), map.keys)
        assertEquals("1", map["x"]?.id)
        assertEquals("2", map["z"]?.id)
        assertNull(map["y"])
    }

    @Test
    fun `models parser understands common alternative shapes`() {
        assertEquals(listOf("a", "b"), ServerLogic.parseModels("""["a"," b "]""").ids)
        assertEquals(listOf("m1"), ServerLogic.parseModels("""{"models":[{"name":"m1"}]}""").ids)
        assertEquals(listOf("r"), ServerLogic.parseModels("""{"result":[{"model":"r"}]}""").ids)
    }

    @Test
    fun `bad models reply shows the start of the body`() {
        val e = try { ServerLogic.parseModels("""{"ok":true,"hint":"x"}"""); null } catch (e: ServerException) { e }
        assertTrue(e!!.error.message.contains("""{"ok":true"""))
    }
}
