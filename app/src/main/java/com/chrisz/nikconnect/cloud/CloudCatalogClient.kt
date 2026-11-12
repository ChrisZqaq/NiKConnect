package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
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
    private val http: CloudHttp = CloudHttp.shared,
) {
    suspend fun fetchCatalog(): List<CloudRecipe> = withContext(Dispatchers.IO) {
        val body = """{"ownerUserId":"nikon","bookId":"$OFFICIAL_BOOK_ID","sortType":0,"lang_code":"zh-cn","country":"CN"}"""
        val raw = http.post("$CWP_HOST/bff/recipe/release", body, emptyMap(), "IF_FR100_RELEASE")
        parseCatalog(raw)
    }

    suspend fun fetchDetail(recipeId: String): CloudRecipeDetail = withContext(Dispatchers.IO) {
        val raw = http.get(
            "$IR_HOST/publicrecipe/0000000000000001/$recipeId?sortType=0&isPagenation=false",
            emptyMap(),
            "IF_IR_R_PR_DETAIL",
        )
        parseDetail(raw)
    }

    // ---- 解析（纯函数，供 JVM 测试） ----

    fun parseCatalog(raw: String): List<CloudRecipe> {
        val root = http.parseObject(raw)
        val arr = (root["recipe"] as? JsonArray)
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
        val root = http.parseObject(raw)
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

    companion object {
        internal val PARAM_LABELS = listOf(
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

        internal val EXIF_LABELS = listOf(
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
