package com.chrisz.nikconnect.cloud

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 自己的 OIDC 授权码流程。
 *
 * 为什么不用网页自带的那套：尼康云创的 Next 前端靠 keycloak-js 在**第三方 iframe** 里读会话
 * （`/protocol/openid-connect/3p-cookie`、`/login-status-iframe`），WebView 里这条路一直判成"未登录"，
 * 结果 SSO 明明成功了页面还是弹回落地页，App 也就抓不到 token。
 *
 * 我们已经验证过：`client_id=c0004` 是**公开客户端**（token 端点不带 secret 也只会回 `invalid_grant`），
 * 且 `redirect_uri=https://imagingcloud.nikon.com.cn/auth/callback` 被服务端接受（非法 URI 才 400）。
 * 所以只要 WebView 里已经登录过（`accounts.cld.nikon.com.cn` 有会话 cookie），
 * 用 `prompt=none` 拉一次授权码就能换到 access_token + refresh_token，全程无需用户再操作。
 */
internal object CloudOidc {

    const val REDIRECT_URI = "https://imagingcloud.nikon.com.cn/auth/callback"

    private const val AUTHORIZE_PATH = "/protocol/openid-connect/auth"
    private const val SCOPE = "openid"

    /**
     * 授权请求 URL。
     *
     * `promptNone = true`：静默模式，有 Keycloak 会话就直接回码，没有就回 `error=login_required`。
     * `promptNone = false`：交互模式，Keycloak 会显示自己的登录页（尼康云创配的是 Nikon ID 代理登录）。
     */
    fun authorizeUrl(state: String, codeChallenge: String? = null, promptNone: Boolean = true): String {
        val q = buildList {
            add("client_id=$CLOUD_CLIENT_ID")
            add("response_type=code")
            add("scope=${urlEncode(SCOPE)}")
            add("redirect_uri=${urlEncode(REDIRECT_URI)}")
            add("state=${urlEncode(state)}")
            if (promptNone) add("prompt=none")
            if (!codeChallenge.isNullOrBlank()) {
                add("code_challenge=$codeChallenge")
                add("code_challenge_method=S256")
            }
        }
        return "$AUTH_HOST$AUTHORIZE_PATH?${q.joinToString("&")}"
    }

    /**
     * 从回跳 URL 里取授权结果：
     * `…/auth/callback?code=xxx&state=yyy` 或 `…?error=login_required&state=yyy`。
     */
    fun parseCallback(url: String?): Callback? {
        val raw = url?.takeIf { it.startsWith(REDIRECT_URI, ignoreCase = true) } ?: return null
        val query = raw.substringAfter('?', "")
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull { pair ->
            val k = pair.substringBefore('=').takeIf { it.isNotBlank() } ?: return@mapNotNull null
            k to java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }.toMap()
        val code = params["code"]?.takeIf { it.isNotBlank() }
        val error = params["error"]?.takeIf { it.isNotBlank() }
        if (code == null && error == null) return null
        return Callback(code = code, error = error, state = params["state"].orEmpty())
    }

    data class Callback(val code: String?, val error: String?, val state: String)

    /** PKCE：随机 verifier 与其 S256 challenge。 */
    fun newPkce(): Pair<String, String> {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = base64Url(bytes)
        return verifier to codeChallenge(verifier)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    fun newState(): String = base64Url(ByteArray(16).also { SecureRandom().nextBytes(it) })

    fun exchangeBody(code: String, redirectUri: String, verifier: String?): String {
        val base = listOf(
            "grant_type=authorization_code",
            "client_id=$CLOUD_CLIENT_ID",
            "code=${urlEncode(code)}",
            "redirect_uri=${urlEncode(redirectUri)}",
        ).toMutableList()
        if (!verifier.isNullOrBlank()) base.add("code_verifier=${urlEncode(verifier)}")
        return base.joinToString("&")
    }

    private fun base64Url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
