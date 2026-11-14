package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 云创业务错误码识别。
 *
 * 回归背景：`E01_0701` 是 HTTP 200 响应体里的业务码，退避重试必须包住「取响应 + 判码」整段；
 * 早期实现只重试传输层，业务频控直接抛给用户。
 */
class CloudHttpTest {

    private val http = CloudHttp(minIntervalMillis = 0, sleep = {})

    @Test
    fun `top level error code is detected`() {
        assertEquals("E01_0701", http.businessErrorCode("""{"error_code":"E01_0701"}"""))
    }

    @Test
    fun `error code inside body is detected`() {
        assertEquals(
            "E01_0701",
            http.businessErrorCode("""{"statusCode":200,"body":{"error_code":"E01_0701"}}"""),
        )
    }

    @Test
    fun `normal payload has no error code`() {
        assertEquals("", http.businessErrorCode("""{"statusCode":200,"body":{"recipe":null}}"""))
        assertEquals("", http.businessErrorCode("""[{"id":"C1"}]"""))
    }

    @Test
    fun `non json response has no error code`() {
        assertEquals("", http.businessErrorCode("<html>gateway</html>"))
        assertEquals("", http.businessErrorCode(""))
    }

    // ---- 频控退避（E01_0701 是 HTTP 200 里的业务码） ----

    @Test
    fun `rate limited response backs off and then succeeds`() = runBlocking {
        val delays = mutableListOf<Long>()
        var calls = 0
        val http = CloudHttp(
            minIntervalMillis = 0,
            sleep = { delays += it },
            perform = {
                calls++
                if (calls <= 2) """{"statusCode":200,"body":{"error_code":"E01_0701"}}"""
                else """{"statusCode":200,"body":{"recipe":[]}}"""
            },
        )
        val raw = http.request("POST", "https://example.invalid/x", "{}", emptyMap(), "TEST")
        assertEquals(3, calls)
        assertEquals(listOf(3_000L, 6_000L), delays)
        assertEquals("""{"statusCode":200,"body":{"recipe":[]}}""", raw)
    }

    @Test
    fun `rate limit gives up after three attempts`() = runBlocking {
        val delays = mutableListOf<Long>()
        var calls = 0
        val http = CloudHttp(
            minIntervalMillis = 0,
            sleep = { delays += it },
            perform = {
                calls++
                """{"error_code":"E01_0701"}"""
            },
        )
        val e = runCatching {
            http.request("GET", "https://example.invalid/y", null, emptyMap(), "TEST")
        }.exceptionOrNull()
        assertEquals(3, calls)
        assertEquals(listOf(3_000L, 6_000L), delays)
        assertEquals("E01_0701", (e as CloudException).errorCode)
    }

    @Test
    fun `other business error is not retried`() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        val http = CloudHttp(
            minIntervalMillis = 0,
            sleep = { delays += it },
            perform = { calls++; """{"error_code":"E01_9999"}""" },
        )
        val e = runCatching {
            http.request("GET", "https://example.invalid/z", null, emptyMap(), "TEST")
        }.exceptionOrNull()
        assertEquals(1, calls)
        assertEquals(emptyList<Long>(), delays)
        assertEquals("E01_9999", (e as CloudException).errorCode)
    }

    @Test
    fun `shared instance is reused`() {
        assertEquals(CloudHttp.shared, CloudHttp.shared)
    }

    // ---- refresh_token 交换（OIDC token 端点） ----

    @Test
    fun `token response yields access and refresh`() {
        val raw = """{"access_token":"a-1","expires_in":300,"refresh_token":"r-1","token_type":"Bearer"}"""
        val pair = parseTokenResponse(raw)!!
        assertEquals("a-1", pair.first)
        assertEquals("r-1", pair.second)
    }

    @Test
    fun `token response without refresh keeps access`() {
        val raw = """{"access_token":"a-2","expires_in":300}"""
        val pair = parseTokenResponse(raw)!!
        assertEquals("a-2", pair.first)
        assertNull(pair.second)
    }

    @Test
    fun `token error response is not a success`() {
        assertNull(parseTokenResponse("""{"error":"invalid_grant","error_description":"Token is not active"}"""))
        assertNull(parseTokenResponse("<html>oops</html>"))
    }
}
