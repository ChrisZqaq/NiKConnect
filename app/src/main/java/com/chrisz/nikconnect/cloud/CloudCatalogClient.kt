package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 云创方案摘要（目录卡片用，零登录可取）。 */
@Serializable
internal data class CloudRecipe(
    val id: String,
    val name: String,
    val pictureControlName: String = "",
    val coverUrl: String? = null,
    val publicDate: String = "",
    val isNew: Boolean = false,
    val likes: Int = 0,
)

/** 单个方案的展示数据（详情页用）。 */
internal data class CloudRecipeDetail(
    val id: String,
    val name: String,
    val author: String,
    val settingName: String,
    val baseName: String,
    val description: String,
    val coverUrl: String?,
    val params: List<CloudParam>,
    val exif: List<CloudParam>,
    val camera: String,
    val lens: String,
    val previousId: String?,
    val previousName: String?,
    val nextId: String?,
    val nextName: String?,
)

internal data class CloudParam(val label: String, val value: String)

/**
 * 尼康云创「创意色彩灵感库」只读目录客户端——**不需要登录**。
 *
 * 目录：`POST https://api.user.cwp.imagingcloud.nikon.com.cn/bff/recipe/release`（sortType=0 才带封面）
 * 详情：`GET https://api.user.ir.imagingcloud.nikon.com.cn/publicrecipe/0000000000000001/{id}`
 *
 * 注意：详情接口有 IP 级频控，连续请求会返回 `E01_0701`，需静默退避；本类内置串行限速。
 */
internal class CloudCatalogClient(
    private val minIntervalMillis: Long = 1_200,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    class CloudException(message: String, val errorCode: String = "", val httpStatus: Int = -1) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val gate = Mutex()
    private var lastRequestAt = 0L

    suspend fun fetchCatalog(): List<CloudRecipe> = withContext(Dispatchers.IO) {
        val body = """{"ownerUserId":"nikon","bookId":"$OFFICIAL_BOOK_ID","sortType":0,"lang_code":"zh-cn","country":"CN"}"""
        val raw = request(
            method = "POST",
            url = "$CWP_HOST/bff/recipe/release",
            body = body,
            callId = "IF_FR100_RELEASE",
        )
        parseCatalog(raw)
    }

    suspend fun fetchDetail(recipeId: String): CloudRecipeDetail = withContext(Dispatchers.IO) {
        val raw = request(
            method = "GET",
            url = "$IR_HOST/publicrecipe/0000000000000001/$recipeId?sortType=0&isPagenation=false",
            body = null,
            callId = "IF_IR_R_PR_DETAIL",
        )
        parseDetail(raw)
    }

    // ---- 解析（纯函数，供 JVM 测试） ----

    fun parseCatalog(raw: String): List<CloudRecipe> {
        val root = parseRoot(raw)
        val arr = (root["recipe"] as? kotlinx.serialization.json.JsonArray)
            ?: throw CloudException("目录响应缺少 recipe 数组：${raw.take(160)}")
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            CloudRecipe(
                id = id,
                name = o.str("name").orEmpty(),
                pictureControlName = o.str("picture_controll_name").orEmpty(),
                coverUrl = (o["img"] as? JsonObject)?.str("url")?.takeIf { it.isNotBlank() },
                publicDate = o.str("public_date").orEmpty(),
                isNew = o.bool("is_new"),
                likes = (o["like"] as? JsonObject)?.int("num") ?: 0,
            )
        }
    }

    fun parseDetail(raw: String): CloudRecipeDetail {
        val root = parseRoot(raw)
        val id = root.str("id") ?: throw CloudException("详情响应缺少 id：${raw.take(160)}")
        val pc = root["pictureControl"] as? JsonObject
        return CloudRecipeDetail(
            id = id,
            name = root.str("name").orEmpty(),
            author = root.str("creatorName").orEmpty(),
            settingName = pc?.str("settingName").orEmpty(),
            baseName = pc?.str("baseName").orEmpty(),
            description = sequenceOf("recipeDetail", "recipeMemo", "recipeOutline")
                .mapNotNull { root.str(it) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .trim(),
            coverUrl = root.str("thumbnail")?.takeIf { it.isNotBlank() },
            params = pc?.let(::extractParams).orEmpty(),
            exif = extractExif(root["exif"] as? JsonObject),
            camera = root.str("camera").orEmpty(),
            lens = root.str("lens").orEmpty(),
            previousId = (root["previous_recipe"] as? JsonObject)?.str("id"),
            previousName = (root["previous_recipe"] as? JsonObject)?.str("name"),
            nextId = (root["next_recipe"] as? JsonObject)?.str("id"),
            nextName = (root["next_recipe"] as? JsonObject)?.str("name"),
        )
    }

    /** 参数顺序与相机菜单一致；`-` 表示该方案未设置此参数，直接省略。 */
    private fun extractParams(pc: JsonObject): List<CloudParam> = PARAM_LABELS.mapNotNull { (key, label) ->
        val value = pc.str(key)?.trim().orEmpty()
        if (value.isBlank() || value == "-") null else CloudParam(label, value)
    }

    private fun extractExif(exif: JsonObject?): List<CloudParam> {
        if (exif == null) return emptyList()
        return EXIF_LABELS.mapNotNull { (key, label) ->
            val value = exif.str(key)?.trim().orEmpty()
            if (value.isBlank() || value == "-" || value == "0") null else CloudParam(label, value)
        }
    }

    private fun parseRoot(raw: String): JsonObject {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw CloudException("云创响应不是合法 JSON：${raw.take(160)}")
        }
        val obj = (element as? JsonObject)?.let { wrapper ->
            // 有些端点把内容包在 body 里（列表接口历史上出现过 statusCode/body 包裹）
            (wrapper["body"] as? JsonObject) ?: wrapper
        } ?: throw CloudException("云创响应顶层不是对象：${raw.take(160)}")
        val code = obj.str("error_code").orEmpty()
        if (code.isNotBlank()) throw CloudException("云创返回错误码 $code", errorCode = code)
        return obj
    }

    // ---- 传输 ----

    private suspend fun request(method: String, url: String, body: String?, callId: String): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return send(method, url, body, callId)
            } catch (e: CloudException) {
                // E01_0701 = 频控静默期，退避后重试；其它错误码立即抛出
                if (e.errorCode != RATE_LIMITED || attempt >= MAX_ATTEMPTS) throw e
                sleep(3_000L * attempt)
            }
        }
    }

    private suspend fun send(method: String, url: String, body: String?, callId: String): String {
        gate.withLock {
            val wait = minIntervalMillis - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) sleep(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Origin", "https://imagingcloud.nikon.com.cn")
            conn.setRequestProperty("Referer", "https://imagingcloud.nikon.com.cn/")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
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
            throw CloudException("$callId 网络失败：${e.message}", httpStatus = -1)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val CWP_HOST = "https://api.user.cwp.imagingcloud.nikon.com.cn"
        const val IR_HOST = "https://api.user.ir.imagingcloud.nikon.com.cn"
        const val OFFICIAL_BOOK_ID = "01HXDNNTHY1AZ6RVMMXSXY3BTK"
        const val RATE_LIMITED = "E01_0701"
        private const val MAX_ATTEMPTS = 3
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36 NiKConnect/0.5"

        private val PARAM_LABELS = listOf(
            "quickSharp" to "快速锐化",
            "sharpening" to "锐化",
            "middleRangeSharp" to "中等锐化",
            "clarity" to "清晰度",
            "contrast" to "对比度",
            "saturation" to "饱和度",
            "highlight" to "高光",
            "shadow" to "阴影",
            "whiteLevel" to "白色色阶",
            "blackLevel" to "黑色色阶",
        )

        private val EXIF_LABELS = listOf(
            "exposureTime" to "快门",
            "fNumber" to "光圈",
            "iso" to "ISO",
            "focalLength" to "焦距",
            "whiteBalance" to "白平衡",
            "exposureBiasValue" to "曝光补偿",
            "exposureMode" to "曝光模式",
        )
    }
}

// ---- 轻量 JSON 读取（容忍数字/布尔/字符串混用） ----

internal fun JsonObject.str(key: String): String? {
    val v = this[key] as? JsonPrimitive ?: return null
    return v.content
}

internal fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

internal fun JsonObject.bool(key: String): Boolean = str(key)?.equals("true", ignoreCase = true) == true

internal fun JsonElement.jsonObjOrNull(): JsonObject? = this as? JsonObject
