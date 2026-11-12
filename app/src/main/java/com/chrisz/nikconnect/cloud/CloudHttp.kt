package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL

/** 云创主机：目录与「可分配方案」在 cwp 主机，账号/相机/详情在 ir 主机。两者不能混用（混用返回 E01_9998）。 */
internal const val CWP_HOST = "https://api.user.cwp.imagingcloud.nikon.com.cn"
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
    private var lastRequestAt = 0L

    suspend fun get(url: String, headers: Map<String, String>, callId: String): String =
        request("GET", url, null, headers, callId)

    suspend fun post(url: String, body: String?, headers: Map<String, String>, callId: String): String =
        request("POST", url, body, headers, callId)

    /** 发送并在频控时退避重试；其它错误码立即抛出。 */
    suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        callId: String,
    ): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return send(method, url, body, headers, callId)
            } catch (e: CloudException) {
                if (e.errorCode != RATE_LIMITED_CODE || attempt >= MAX_ATTEMPTS) throw e
                sleep(3_000L * attempt)
            }
        }
    }

    private suspend fun send(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        callId: String,
    ): String {
        gate.withLock {
            val wait = minIntervalMillis - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) sleep(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", CLOUD_USER_AGENT)
            conn.setRequestProperty("Origin", CLOUD_ORIGIN)
            conn.setRequestProperty("Referer", "$CLOUD_ORIGIN/")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw CloudException("$callId HTTP $code", httpStatus = code)
            if (code !in 200..299) throw CloudException("$callId HTTP $code：${text.take(160)}", httpStatus = code)
            return text
        } catch (e: CloudException) {
            throw e
        } catch (e: Exception) {
            throw CloudException("$callId 网络失败：${e.message}")
        } finally {
            conn.disconnect()
        }
    }

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
    }
}
