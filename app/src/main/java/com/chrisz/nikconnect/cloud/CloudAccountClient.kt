package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 已注册到云创账号的相机。 */
internal data class CloudCamera(
    val id: String,
    val name: String,
    val model: String,
)

/**
 * 相机上的云创槽位。云创方案占的是相机独立存储区的 9 个位，
 * 与机身菜单里的 C-1…C-9 不是同一个区（要进 C 槽还得在相机内「保存/编辑」复制）。
 */
internal data class CloudSlot(
    val index: Int,
    val recipeId: String?,
    val name: String,
    val pictureControlName: String,
    val modifiedAt: String,
) {
    val occupied: Boolean get() = !recipeId.isNullOrBlank() || name.isNotBlank()
}

/**
 * 云创账号侧接口（需要 access_token + 账号 guid）——批次 1 只读。
 *
 * 端点来自 Web 端 chunk 反解（`docs/references/NIKON_CLOUD_RESEARCH.md` §6）：
 * - `IF_IR_R_C001` GET  `/camera/{guid}/`              已注册相机列表
 * - `IF_IR_R_C002` GET  `/camera/{guid}/{cameraId}`    9 个槽位的排布
 * - `IF_FR100_H13` POST `/bff/recipe/camera`           可分配的方案（header access_token）
 *
 * 响应形状未在真机核验，解析一律「按特征嗅探」，失败时把原始响应交给 UI 显示，便于截图定位。
 */
internal class CloudAccountClient(
    private val accessToken: String,
    private val guid: String,
    private val country: String = "CN",
    private val http: CloudHttp = CloudHttp(),
) {
    private val bearer = mapOf("Authorization" to "Bearer $accessToken")

    suspend fun listCameras(): List<CloudCamera> = withContext(Dispatchers.IO) {
        val raw = http.get("$IR_HOST/camera/$guid/", bearer, "IF_IR_R_C001")
        parseCameras(raw)
    }

    suspend fun cameraSlots(cameraId: String): List<CloudSlot> = withContext(Dispatchers.IO) {
        val raw = http.get("$IR_HOST/camera/$guid/$cameraId", bearer, "IF_IR_R_C002")
        parseSlots(raw)
    }

    suspend fun assignableRecipes(): List<CloudRecipe> = withContext(Dispatchers.IO) {
        val raw = http.post(
            "$CWP_HOST/bff/recipe/camera",
            """{"country":"$country"}""",
            mapOf("access_token" to accessToken),
            "IF_FR100_H13",
        )
        parseAssignable(raw)
    }

    // ---- 解析（纯函数，供 JVM 测试） ----

    fun parseCameras(raw: String): List<CloudCamera> {
        val element = http.parseElement(raw, "IF_IR_R_C001")
        val arr = http.findObjectArray(element, CAMERA_KEYS, mustHave = "id")
            ?: http.findObjectArray(element, CAMERA_KEYS, mustHave = "cameraId")
            ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: o.str("cameraId") ?: return@mapNotNull null
            CloudCamera(
                id = id,
                name = firstNonBlank(o, "name", "cameraName", "nickname", "displayName").orEmpty(),
                model = firstNonBlank(o, "model", "modelName", "productName", "cameraModel").orEmpty(),
            )
        }
    }

    fun parseSlots(raw: String): List<CloudSlot> {
        val element = http.parseElement(raw, "IF_IR_R_C002")
        val arr = http.findObjectArray(element, SLOT_KEYS, mustHave = "pictureControlName")
            ?: http.findObjectArray(element, SLOT_KEYS, mustHave = "exist")
            ?: http.findObjectArray(element, SLOT_KEYS, mustHave = "id")
            ?: return emptyList()
        return arr.mapIndexedNotNull { index, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            // 槽位号以数组顺序为准（Web 端把数组顺序直接当槽位）；若响应自带 slot/slotNum 则优先用
            val declared = o.int("slot") ?: o.int("slotNum") ?: o.int("slot_num")
            val recipeId = firstNonBlank(o, "id", "recipeId", "recipe_id")
                ?.takeIf { o.bool("exist") || it.isNotBlank() }
            CloudSlot(
                index = declared ?: (index + 1),
                recipeId = recipeId?.takeIf { it.isNotBlank() && it != "0" },
                name = firstNonBlank(o, "name", "recipeName", "recipe_name").orEmpty(),
                pictureControlName = firstNonBlank(o, "pictureControlName", "picture_control_name").orEmpty(),
                modifiedAt = firstNonBlank(o, "modifyDate", "modify_date", "updated").orEmpty(),
            )
        }.sortedBy { it.index }
    }

    fun parseAssignable(raw: String): List<CloudRecipe> {
        val element = http.parseElement(raw, "IF_FR100_H13")
        val arr: JsonArray = http.findObjectArray(element, ASSIGNABLE_KEYS, mustHave = "id") ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            CloudRecipe(
                id = id,
                name = o.str("name").orEmpty(),
                pictureControlName = firstNonBlank(o, "picture_controll_name", "pictureControlName").orEmpty(),
                coverUrl = (o["img"] as? JsonObject)?.str("url")?.takeIf { it.isNotBlank() }
                    ?: o.str("thumbnail")?.takeIf { it.isNotBlank() },
                publicDate = firstNonBlank(o, "public_date", "publicDate").orEmpty(),
                isNew = o.bool("is_new"),
                likes = (o["like"] as? JsonObject)?.int("num") ?: 0,
            )
        }
    }

    private fun firstNonBlank(o: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> o.str(key)?.takeIf { it.isNotBlank() } }

    companion object {
        private val CAMERA_KEYS = listOf("cameraList", "cameras", "camera", "list", "items")
        private val SLOT_KEYS = listOf("recipes", "recipe", "cameraRecipes", "slots", "cameraSlots", "list", "items")
        private val ASSIGNABLE_KEYS = listOf("recipe", "recipes", "list", "items", "cameraRecipes")
    }
}
