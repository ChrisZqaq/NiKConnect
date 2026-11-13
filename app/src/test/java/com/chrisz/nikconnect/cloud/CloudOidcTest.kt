package com.chrisz.nikconnect.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudOidcTest {

    @Test
    fun authorizeUrlCarriesClientRedirectAndPromptNone() {
        val url = CloudOidc.authorizeUrl("st8", "ch4llenge")
        assertTrue(url.startsWith(AUTH_HOST))
        assertTrue(url.contains("client_id=c0004"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("prompt=none"))
        assertTrue(url.contains("code_challenge=ch4llenge"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_uri=${java.net.URLEncoder.encode(CloudOidc.REDIRECT_URI, "UTF-8")}"))
    }

    @Test
    fun authorizeUrlOmitsPkceWhenNotAsked() {
        assertTrue(!CloudOidc.authorizeUrl("s").contains("code_challenge"))
    }

    @Test
    fun parseCallbackReadsCode() {
        val cb = CloudOidc.parseCallback("${CloudOidc.REDIRECT_URI}?code=abc123&state=st8")
        assertNotNull(cb)
        assertEquals("abc123", cb!!.code)
        assertEquals("st8", cb.state)
        assertNull(cb.error)
    }

    @Test
    fun parseCallbackReadsError() {
        val cb = CloudOidc.parseCallback("${CloudOidc.REDIRECT_URI}?error=login_required&state=st8")
        assertEquals("login_required", cb!!.error)
        assertNull(cb.code)
    }

    @Test
    fun parseCallbackIgnoresForeignUrl() {
        assertNull(CloudOidc.parseCallback("https://imagingcloud.nikon.com.cn/home?code=x"))
        assertNull(CloudOidc.parseCallback(null))
        assertNull(CloudOidc.parseCallback("${CloudOidc.REDIRECT_URI}"))
    }

    @Test
    fun pkceChallengeIsS256OfVerifier() {
        val (verifier, challenge) = CloudOidc.newPkce()
        assertEquals(43, verifier.length)              // 32 字节 → 43 字符 base64url 无填充
        assertEquals(43, challenge.length)
        assertEquals(CloudOidc.codeChallenge(verifier), challenge)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun exchangeBodyIncludesVerifierOnlyWhenPresent() {
        val withPkce = CloudOidc.exchangeBody("c0de", CloudOidc.REDIRECT_URI, "v3r")
        assertTrue(withPkce.contains("grant_type=authorization_code"))
        assertTrue(withPkce.contains("client_id=c0004"))
        assertTrue(withPkce.contains("code=c0de"))
        assertTrue(withPkce.contains("code_verifier=v3r"))
        assertTrue(
            withPkce.contains(
                "redirect_uri=${java.net.URLEncoder.encode(CloudOidc.REDIRECT_URI, "UTF-8")}",
            ),
        )
        assertTrue(!CloudOidc.exchangeBody("c0de", CloudOidc.REDIRECT_URI, null).contains("code_verifier"))
    }
}
