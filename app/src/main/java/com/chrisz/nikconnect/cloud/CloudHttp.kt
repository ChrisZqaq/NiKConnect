package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL

/** 云创主机：目录与「可分配方案」在 cwp 主机，账号/相机/详情在 ir 主机。两者不能混用（混用返回 E01_9998）。 */
internal const val CWP_HOST = "https://api.user.cwp.imagingcloud.nikon.com.cn"
/**
 * 账号中心（Keycloak realm=user，resource c0004）。值取自
 * `https://imagingcloud.nikon.com.cn/json/keycloak_Prod.json`。
 * `userinfo` 是**账号 ID 的唯一权威来源**：Web 端也用它拿 `guid`。
 */
internal const val AUTH_HOST = "https://auth.accounts.cld.nikon.com.cn/auth/realms/user"

/** Keycloak 公共客户端 id（来自 `https://imagingcloud.nikon.com.cn/json/keycloak_Prod.json` 的 resource）。 */
internal const val CLOUD_CLIENT_ID = "c0004"

internal const val IR_HOST = "https://api.user.ir.imagingcloud.nikon.com.cn"
internal const val CLOUD_ORIGIN = "https://imagingcloud.nikon.com.cn"
/** 云创网页入口（方案库首页）。 */
internal const val CLOUD_INDEX_URL = "$CLOUD_ORIGIN/recipe/index/"

/** 云创网页的「相机」页：打开后网页自己会发 `IF_IR_R_C001`，从请求 URL 就能读到真实账号 guid。 */
internal const val CLOUD_CAMERA_URL = "$CLOUD_ORIGIN/recipe/camera"

internal const val CLOUD_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36 NiKConnect/0.6"

/** 官方方案书（零登录目录就是这本书）。 */
internal const val OFFICIAL_BOOK_ID = "01HXDNNTHY1AZ6RVMMXSXY3BTK"

/** 频控错误码：短时间密集请求后出现，静默约 45 秒。 */
internal const val RATE_LIMITED_CODE = "E01_0701"

internal class CloudException(
    message: String,
    val errorCode: String = "",
    val httpStatus: Int = -1,
) : Exception(message)

/**
 * 云创 HTTP 传输的唯一入口。
 *
 * CLAUDE.md 硬约束：所有云创请求复用同一入口，串行限速 + `E01_0701` 退避，新增端点不要各自开连接。
 * 认证头由调用方传：目录用 `access_token`，账号/相机接口用 `Authorization: Bearer`。
 */
internal class CloudHttp(
    private val minIntervalMillis: Long = 1_200,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Mutex()
    private var lastRequestAt = Long.MIN_VALUE / 2

    suspend fun get(url: String, headers: Map<String, String>, callId: String): String =
        request("GET", url, null, headers, callId)

    suspend fun patch(
        url: String,
        body: String,
        headers: Map<String, String>,
        callId: String,
        contentType: String = "text/plain;charset=UTF-8",
    ): String = request("PATCH", url, body, headers, callId, contentType)

    suspend fun post(
        url: String,
        body: String?,
        headers: Map<String, String>,
        callId: String,
        contentType: String = "text/plain;charset=UTF-8",
    ): String = request("POST", url, body, headers, callId, contentType)

    /**
     * 发送并在频控时退避重试；其它错误码立即抛出。
     *
     * 关键：业务频控码 `E01_0701` 是 **HTTP 200** 里返回的，属于"响应体里的错误"，
     * 所以退避必须包住「取响应 + 判业务码」整段，只重试 `send()` 是漏的。
     */
    suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        callId: String,
        contentType: String = "text/plain;charset=UTF-8",
        omitOrigin: Boolean = false,
    ): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val raw = send(method, url, body, headers, callId, contentType, omitOrigin)
                val code = businessErrorCode(raw)
                if (code.isNotBlank()) throw CloudException("$callId 返回错误码 $code", errorCode = code)
                return raw
            } catch (e: CloudException) {
                if (e.errorCode != RATE_LIMITED_CODE || attempt >= MAX_ATTEMPTS) throw e
                // 退避时让出 gate：频控是服务端侧冷却，不是我们自己的节奏问题
                sleep(3_000L * attempt)
            }
        }
    }

    /** 纯函数：从原始响应里读业务错误码（顶层或 `body` 包裹层里），没有则返回空串。 */
    fun businessErrorCode(raw: String): String {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return ""
        if (element !is JsonObject) return ""
        element.str("error_code")?.takeIf { it.isNotBlank() }?.let { return it }
        val body = element["body"]
        if (body is JsonObject) body.str("error_code")?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private suspend fun send(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        callId: String,
        contentType: String = "text/plain;charset=UTF-8",
        omitOrigin: Boolean = false,
    ): String {
        // gate 覆盖「等间隔 + 发请求 + 读响应」全过程：慢请求不会与后续请求重叠（进程内单飞）。
        gate.withLock {
            val wait = minIntervalMillis - (nowMillis() - lastRequestAt)
            if (wait > 0) sleep(wait)
            lastRequestAt = nowMillis()
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 25_000
                conn.setRequestProperty("User-Agent", CLOUD_USER_AGENT)
                if (!omitOrigin) {
                    conn.setRequestProperty("Origin", CLOUD_ORIGIN)
                    conn.setRequestProperty("Referer", "$CLOUD_ORIGIN/")
                }
                conn.setRequestProperty("Accept", "application/json, text/plain, */*")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.setRequestProperty("Content-Type", contentType)
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                log("$callId $method $url -> $code ${body?.take(200).orEmpty()} | ${text.take(200)}")
                CloudDebug.record("$callId-$code", "$method $url\n${body.orEmpty()}", text)
                if (code == 401 || code == 403) throw CloudException("$callId HTTP $code", httpStatus = code)
                if (code !in 200..299) throw CloudException("$callId HTTP $code：${text.take(160)}", httpStatus = code)
                return text
            } catch (e: CloudException) {
                throw e
            } catch (e: Exception) {
                log("$callId $method $url -> 网络失败：${e.message}")
                throw CloudException("$callId 网络失败：${e.message}")
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 请求/响应的单行日志（含状态码与前后 200 字）。
     *
     * 作用：真机出问题时 `adb logcat -s NikConnect` 就能看到是哪个 callId、什么状态码、
     * 服务端说了什么，不必让用户截图。JVM 单测里 android.util.Log 是桩，`runCatching` 兜住。
     */
    private fun log(line: String) {
        runCatching { android.util.Log.d("NikConnect", line) }
    }

    /** 单调时钟：系统时间被改动时不会把间隔算错。 */
    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    // ---- 响应解析（纯函数，供 JVM 测试） ----

    /** 解析为对象：解 `body` 包裹层，遇到 `error_code` 直接抛错。 */
    fun parseObject(raw: String, callId: String = ""): JsonObject {
        val element = parseElement(raw, callId)
        val wrapper = element as? JsonObject
            ?: throw CloudException("$callId 响应顶层不是对象：${raw.take(160)}")
        val obj = (wrapper["body"] as? JsonObject) ?: wrapper
        val code = obj.str("error_code").orEmpty()
        if (code.isNotBlank()) throw CloudException("$callId 返回错误码 $code", errorCode = code)
        return obj
    }

    /**
     * 用 refresh_token 换新的 access_token（Keycloak 公共客户端，表单提交）。
     *
     * 为什么必须有：尼康云创的 access_token 只有几十分钟，光把它放进 Keystore 只能做到
     * 「几十分钟内不用重登」。refresh_token 是天级有效期，续上之后才算真正的免重登。
     * 换不到（refresh_token 过期/被吊销）返回 null，由调用方引导用户重登。
     */
    suspend fun refreshAccessToken(refreshToken: String): Pair<String, String?>? {
        if (refreshToken.isBlank()) return null
        val form = buildString {
            append("grant_type=refresh_token")
            append("&client_id=").append(CLOUD_CLIENT_ID)
            append("&refresh_token=").append(java.net.URLEncoder.encode(refreshToken, "UTF-8"))
        }
        val raw = runCatching {
            send(
                "POST",
                "$AUTH_HOST/protocol/openid-connect/token",
                form,
                emptyMap(),
                "OIDC_REFRESH",
                contentType = "application/x-www-form-urlencoded",
            )
        }.getOrNull() ?: return null
        return parseTokenResponse(raw)
    }

    fun parseElement(raw: String, callId: String = ""): JsonElement {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw CloudException("$callId 响应不是合法 JSON：${raw.take(160)}")
        }
        // 有些端点把业务数据放在 body 里，且 body 可能直接是数组
        if (element is JsonObject) {
            val code = element.str("error_code").orEmpty()
            if (code.isNotBlank()) throw CloudException("$callId 返回错误码 $code", errorCode = code)
            val body = element["body"]
            if (body is JsonObject) {
                val innerCode = body.str("error_code").orEmpty()
                if (innerCode.isNotBlank()) throw CloudException("$callId 返回错误码 $innerCode", errorCode = innerCode)
                return body
            }
            if (body is JsonArray) return body
        }
        return element
    }

    /**
     * 在响应里找「对象数组」：按候选键依次取，取不到再递归找第一个元素含 [mustHave] 键的数组。
     * 云创不同端点把列表放在 cameraList / cameras / recipes / camerasInfo 等不同键下，且形状未逐一验证，
     * 所以宁可按特征嗅探，也不写死单一路径。
     */
    fun findObjectArray(element: JsonElement, keys: List<String>, mustHave: String): JsonArray? {
        if (element is JsonArray) {
            val hit = element.takeIf { arr -> arr.any { it is JsonObject && it.containsKey(mustHave) } }
            if (hit != null) return hit
        }
        if (element is JsonObject) {
            keys.forEach { key ->
                val candidate = element[key]
                if (candidate is JsonArray) {
                    val hit = candidate.takeIf { arr -> arr.any { it is JsonObject && it.containsKey(mustHave) } }
                    if (hit != null) return hit
                }
            }
        }
        return deepFindArray(element, mustHave)
    }

    private fun deepFindArray(element: JsonElement, mustHave: String): JsonArray? {
        when (element) {
            is JsonArray -> {
                if (element.any { it is JsonObject && it.containsKey(mustHave) }) return element
                element.forEach { child -> deepFindArray(child, mustHave)?.let { return it } }
            }
            is JsonObject -> element.values.forEach { child ->
                deepFindArray(child, mustHave)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    companion object {
        private const val MAX_ATTEMPTS = 3

        /** 进程内共享实例：限速与退避是账号/IP 维度的，多个客户端各建一份等于没限速。 */
        internal val shared: CloudHttp by lazy { CloudHttp() }
    }
}

/**
 * `POST {auth}/protocol/openid-connect/token` 的响应解析（纯函数，便于单测）。
 *
 * 尼康云创是标准 Keycloak 公共客户端（`resource=c0004`）：
 * `grant_type=refresh_token` 成功时返回 `access_token` / `refresh_token` / `expires_in`。
 */
internal fun parseTokenResponse(raw: String): Pair<String, String?>? {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    val access = element.str("access_token")?.trim().orEmpty()
    if (access.isBlank()) return null
    val refresh = element.str("refresh_token")?.trim()?.takeIf { it.isNotBlank() }
    return access to refresh
}
