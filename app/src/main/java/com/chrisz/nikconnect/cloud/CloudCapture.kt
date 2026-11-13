package com.chrisz.nikconnect.cloud

/**
 * 凭据抓取的纯函数集合（从 WebView 回调里抽出来，便于纯 JVM 测试）。
 *
 * 背景：尼康云创的 access_token 既可能出现在请求头 `access_token` 里（Web 端调 `IF_FR*` 接口时），
 * 也可能出现在 `Authorization: Bearer` 里（调 `IF_IR_*` 接口时），还可能出现在回跳 URL 的
 * query/fragment 中。真机上「点了登录没反应」有两次都是这一层的判断写窄了，所以这里逐条锁死。
 */
internal object CloudCapture {

    /** access_token 至少这么长才算真的（JWT 都远超这个长度）。 */
    private const val MIN_TOKEN_LENGTH = 40

    /** 云创 token：优先 `access_token`，其次 `Authorization: Bearer`。 */
    fun accessTokenFromHeaders(headers: Map<String, String>?): String? {
        if (headers.isNullOrEmpty()) return null
        headers.entries.firstOrNull { it.key.equals("access_token", ignoreCase = true) }
            ?.value?.trim()?.trim('"')?.takeIf { it.length > MIN_TOKEN_LENGTH }
            ?.let { return it }
        headers.entries.firstOrNull { it.key.equals("authorization", ignoreCase = true) }
            ?.value?.trim()
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()?.trim('"')?.takeIf { it.length > MIN_TOKEN_LENGTH }
            ?.let { return it }
        return null
    }

    /** 账号 guid：`guid` / `x-guid` / `user-guid` / `userid` 等常见命名，值必须像 guid。 */
    fun guidFromHeaders(headers: Map<String, String>?): String? {
        if (headers.isNullOrEmpty()) return null
        return headers.entries
            .filter { entry ->
                val key = entry.key.lowercase()
                key == "guid" || key == "userid" || key.startsWith("x-") && key.contains("guid") ||
                    key.contains("guid")
            }
            .mapNotNull { it.value.trim().trim('"').takeIf(::isGuidLike) }
            .firstOrNull()
    }

    /** OAuth 隐式/查询串回跳：`#access_token=…` 与 `?access_token=…`。 */
    fun accessTokenFromUrl(url: String?): String? {
        val text = url?.takeIf { it.isNotBlank() } ?: return null
        val marker = "access_token="
        val at = text.indexOf(marker)
        if (at < 0) return null
        return text.substring(at + marker.length)
            .substringBefore('&')
            .substringBefore('#')
            .takeIf { it.length > MIN_TOKEN_LENGTH }
    }

    /**
     * 解析登录页 JS 探针的返回值。
     *
     * 探针返回 `token|guid`（任一侧可为空）；undefined/null/空串都当作没抓到。
     */
    fun parseProbeResult(raw: String?): Pair<String?, String?> {
        val text = raw?.trim()?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" && it != "undefined" }
            ?: return null to null
        val token = text.substringBefore('|').trim().takeIf { it.length > MIN_TOKEN_LENGTH }
        val guid = text.substringAfter('|', "").trim().takeIf { it.isNotBlank() && isGuidLike(it) }
        return token to guid
    }
}
