package com.chrisz.nikconnect.cloud

import org.junit.Assert.assertEquals
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

    @Test
    fun `shared instance is reused`() {
        assertEquals(CloudHttp.shared, CloudHttp.shared)
    }
}
