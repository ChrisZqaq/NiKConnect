package com.chrisz.nikconnect.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudCaptureTest {

    private val token = "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJhYmMifQ." + "x".repeat(60)
    private val guid = "0000000000062160"

    @Test
    fun accessTokenPrefersAccessTokenHeader() {
        val headers = mapOf(
            "access_token" to token,
            "Authorization" to "Bearer another-token-that-is-long-enough-to-pass-length-check",
        )
        assertEquals(token, CloudCapture.accessTokenFromHeaders(headers))
    }

    @Test
    fun accessTokenFallsBackToBearer() {
        val headers = mapOf("authorization" to "Bearer $token")
        assertEquals(token, CloudCapture.accessTokenFromHeaders(headers))
    }

    @Test
    fun accessTokenIgnoresShortValuesAndNonBearer() {
        assertNull(CloudCapture.accessTokenFromHeaders(mapOf("access_token" to "short")))
        assertNull(CloudCapture.accessTokenFromHeaders(mapOf("authorization" to "Basic $token")))
        assertNull(CloudCapture.accessTokenFromHeaders(null))
    }

    @Test
    fun guidFromHeadersAcceptsCommonSpellings() {
        assertEquals(guid, CloudCapture.guidFromHeaders(mapOf("X-Guid" to guid)))
        assertEquals(guid, CloudCapture.guidFromHeaders(mapOf("user-guid" to guid)))
        assertNull(CloudCapture.guidFromHeaders(mapOf("guid" to "not-a-guid")))
    }

    @Test
    fun accessTokenFromUrlHandlesQueryAndFragment() {
        assertEquals(token, CloudCapture.accessTokenFromUrl("https://imagingcloud.nikon.com.cn/home?access_token=$token&state=x"))
        assertEquals(token, CloudCapture.accessTokenFromUrl("https://imagingcloud.nikon.com.cn/home#access_token=$token"))
        assertNull(CloudCapture.accessTokenFromUrl("https://imagingcloud.nikon.com.cn/home"))
    }

    @Test
    fun parseProbeResultSplitsTokenAndGuid() {
        val (t, g) = CloudCapture.parseProbeResult("\"$token|$guid\"")
        assertEquals(token, t)
        assertEquals(guid, g)
    }

    @Test
    fun parseProbeResultAcceptsGuidOnly() {
        val (t, g) = CloudCapture.parseProbeResult("\"|$guid\"")
        assertNull(t)
        assertEquals(guid, g)
    }

    @Test
    fun parseProbeResultTreatsEmptyAsNothing() {
        assertEquals(null to null, CloudCapture.parseProbeResult(""))
        assertEquals(null to null, CloudCapture.parseProbeResult("null"))
        assertEquals(null to null, CloudCapture.parseProbeResult("undefined"))
        assertEquals(null to null, CloudCapture.parseProbeResult(null))
    }
}
