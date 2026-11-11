package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 尼康云创（Nikon Imaging Cloud）API 客户端。
 *
 * 仅元数据：方案列表浏览与收藏（加入相机注册列表），NP3 文件不经过 App。
 * 认证：Keycloak realm=user，clientId=c0004（https://auth.accounts.cld.nikon.com.cn）。
 * 列表接口用 header `access_token`（非 Bearer），其余接口用 Bearer。
 */
class NikonCloudClient(
    private val accessToken: String,
    private val guid: String,
    private val country: String = "CN",
    private val langCode: String = "zh-CN",
) {
    @Serializable
    data class RecipeSummary(
        val id: String,
        val name: String,
        val creator: String = "",
        val thumbnailUrl: String = "",
        val camera: String = "",
    )

    @Serializable
    data class RecipeListResult(
        val recipes: List<RecipeSummary>,
        val totalCount: Int,
    )

    class CloudException(message: String, val httpStatus: Int = -1) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 拉取方案列表（IF_FR100_H05：POST /bff/recipe，header access_token）。响应体形状待真机登录后核对，解析失败时抛 CloudException 由 UI 兜底。 */
    suspend fun listRecipes(filter: String = "{}"): RecipeListResult = withContext(Dispatchers.IO) {
        val body = """{"filter":$filter,"country":"$country","lang_code":"$langCode"}"""
        val raw = postJson(
            url = "https://api.user.cwp.imagingcloud.nikon.com.cn/bff/recipe",
            headers = mapOf("access_token" to accessToken),
            body = body,
            callId = "IF_FR100_H05",
        )
        parseRecipeList(raw)
    }

    /** 收藏方案到指定书（IF_IR_R_PR001：POST /publicrecipe/{guid}，Bearer）。 */
    suspend fun addRecipesToBook(bookId: String, recipeIds: List<String>) = withContext(Dispatchers.IO) {
        val body = """{"bookId":"$bookId","recipeIds":${json.encodeToString(ListSerializer(String.serializer()), recipeIds)}}"""
        postJson(
            url = "https://api.user.ir.imagingcloud.nikon.com.cn/publicrecipe/$guid",
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            body = body,
            callId = "IF_IR_R_PR001",
        )
        Unit
    }

    /** 查询已注册相机列表（GET /camera/{guid}，Bearer）。 */
    suspend fun listCameras(): String = withContext(Dispatchers.IO) {
        get(
            url = "https://api.user.ir.imagingcloud.nikon.com.cn/camera/$guid/",
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            callId = "IF_IR_R_C001",
        )
    }

    /** 解析 /bff/recipe 响应。当前形状未知（需登录真机核对）；先尝试常见包裹层级，失败抛异常。 */
    internal fun parseRecipeList(raw: String): RecipeListResult {
        val root = try {
            json.parseToJsonElement(raw).jsonObjectOrNull() ?: throw CloudException("云创响应顶层不是对象：${raw.take(120)}", -1)
        } catch (e: CloudException) {
            throw e
        } catch (e: Exception) {
            throw CloudException("云创响应不是合法 JSON：${raw.take(120)}", -1)
        }
        // 常见包裹：{recipes:[...], total} / {data:{recipes:[...]}} / {result:{...}}
        val container = root["recipes"]?.jsonArrayOrNull()
            ?: root["data"]?.jsonObjectOrNull()?.get("recipes")?.jsonArrayOrNull()
            ?: root["result"]?.jsonObjectOrNull()?.get("recipes")?.jsonArrayOrNull()
            ?: throw CloudException("未识别的方案列表响应形状：${raw.take(200)}")
        val recipes = container.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            RecipeSummary(
                id = (o["id"] ?: o["recipeId"])?.jsonPrimitiveOrNull()?.content ?: return@mapNotNull null,
                name = (o["name"] ?: o["recipeName"] ?: o["title"])?.jsonPrimitiveOrNull()?.content ?: "",
                creator = (o["creator"] ?: o["creatorName"])?.jsonPrimitiveOrNull()?.content ?: "",
                thumbnailUrl = (o["thumbnailUrl"] ?: o["thumbnail"] ?: o["image"])?.jsonPrimitiveOrNull()?.content ?: "",
                camera = (o["camera"] ?: o["cameraModel"])?.jsonPrimitiveOrNull()?.content ?: "",
            )
        }
        val total = (root["total"] ?: root["totalCount"])?.jsonPrimitiveOrNull()?.content?.toIntOrNull() ?: recipes.size
        return RecipeListResult(recipes, total)
    }

    private fun postJson(url: String, headers: Map<String, String>, body: String, callId: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw CloudException("$callId HTTP $code：${text.take(160)}", code)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String, headers: Map<String, String>, callId: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) throw CloudException("$callId HTTP $code：${text.take(160)}", code)
            return text
        } finally {
            conn.disconnect()
        }
    }
}

// 轻量 JsonElement 帮助方法（避免顶层扩展污染）
internal fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): kotlinx.serialization.json.JsonObject? =
    this as? kotlinx.serialization.json.JsonObject

internal fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
    this as? kotlinx.serialization.json.JsonArray

internal fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): kotlinx.serialization.json.JsonPrimitive? =
    this as? kotlinx.serialization.json.JsonPrimitive
