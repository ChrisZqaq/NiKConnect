package com.chrisz.nikconnect.cloud

import android.util.Base64
import org.json.JSONObject

/** 云创账号 guid 的两种可能形态：26 位 ULID（Crockford Base32）或 UUID。 */
internal val ULID_PATTERN = Regex("[0-9A-HJKMNP-TV-Z]{26}")
internal val UUID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** 账号 guid 出现在这些路径段之后：`/camera/{guid}/`、`/publicrecipe/{guid}`… */
private val GUID_PATH_KEYWORDS = listOf("camera", "publicrecipe", "localrecipe", "publicbook")

internal fun isGuidLike(value: String): Boolean =
    ULID_PATTERN.matches(value) || UUID_PATTERN.matches(value)

/**
 * 从请求 URL 里取账号 guid（语义提取，不是"随便找一个 ULID"）。
 *
 * 早期版本取 URL 里第一个 ULID，结果把 `/publicrecipe/0000000000000001/{recipeId}` 里的**方案 ID**
 * 当成账号 guid，接着 `GET /camera/{guid}/` 就被网关以 422 拒掉。这里只认紧跟关键字那一段。
 */
internal fun guidFromUrl(url: String?): String? {
    val raw = url.orEmpty()
    if (raw.isBlank()) return null
    queryGuid(raw)?.let { return it }
    val segments = raw.substringBefore('?').substringBefore('#')
        .split('/')
        .filter { it.isNotBlank() }
    segments.forEachIndexed { index, segment ->
        if (GUID_PATH_KEYWORDS.any { it.equals(segment, ignoreCase = true) }) {
            val next = segments.getOrNull(index + 1) ?: return null
            // 只认紧邻关键字的那一段；不是 guid 形态就直接放弃，不往后找（避免抓到方案 ID）
            return next.takeIf(::isGuidLike)
        }
    }
    return null
}

/** `?guid=` / `&userGuid=` / `#guid=` 这类查询串或片段里直接带 guid 的情况。 */
private fun queryGuid(url: String): String? =
    Regex("[?&#](?:user_?)?guid=([^&#]+)", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.getOrNull(1)?.let { if (isGuidLike(it)) it else null }

/** 兜底：从 JWT 载荷里读 guid 相关 claim（guid / userGuid / userId / sub…）。 */
internal fun guidFromJwt(token: String?): String? {
    val payload = token?.split('.')?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
    val decoded = runCatching {
        String(
            Base64.decode(
                payload,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            ),
            Charsets.UTF_8,
        )
    }.getOrNull() ?: return null
    val claims = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
    val values = listOf("guid", "userGuid", "user_guid", "userId", "user_id", "uid", "sub")
        .map { claims.optString(it).trim() }
        .filter { it.isNotEmpty() }
    return values.firstOrNull(::isGuidLike) ?: values.firstOrNull { it.length in 8..64 }
}

/** 依次尝试的 guid 候选：抓取到的值优先，其次 token 自带的 claim。 */
internal fun guidCandidates(token: String?, captured: String?): List<String> =
    listOfNotNull(
        captured?.trim()?.takeIf { it.isNotEmpty() },
        guidFromJwt(token)?.trim()?.takeIf { it.isNotEmpty() },
    ).distinct()
