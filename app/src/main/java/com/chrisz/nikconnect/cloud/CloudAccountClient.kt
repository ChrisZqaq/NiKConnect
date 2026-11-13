package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 已注册到云创账号的相机。 */
internal data class CloudCamera(
    val id: String,
    val name: String,
    val model: String,
    val serial: String = "",
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
 * 一次槽位排布提交项。Web 端 PATCH 的 body 就是 `{recipes:[{slotNo,recipeId}]}`，
 * 且**只提交已占用的槽位**（空位靠"不在数组里"表示，不是 `null`）。
 */
internal data class CloudSlotAssignment(
    val slotNo: Int,
    val recipeId: String,
)

/** 账号中心（Keycloak userinfo）返回的身份信息：账号 ID 的唯一权威来源。 */
internal data class CloudIdentity(
    val guid: String,
    val language: String = "",
    val country: String = "",
    val subject: String = "",
)

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
    private val http: CloudHttp = CloudHttp.shared,
) {
    private val bearer = mapOf("Authorization" to "Bearer $accessToken")

    /**
     * 取账号身份。
     *
     * 这是 Web 端拿 `guid` 的原路径（`_app` chunk：`keycloak.loadUserInfo()` 之后
     * `guid = e.guid`），比从请求 URL 里猜 ULID 可靠得多——账号页 422 的根因就是猜。
     */
    suspend fun fetchIdentity(): CloudIdentity = withContext(Dispatchers.IO) {
        val raw = http.get("$AUTH_HOST/protocol/openid-connect/userinfo", bearer, "OIDC_USERINFO")
        parseIdentity(raw)
    }

    suspend fun listCameras(): List<CloudCamera> = withContext(Dispatchers.IO) {
        val raw = http.get("$IR_HOST/camera/$guid/", bearer, "IF_IR_R_C001")
        parseCameras(raw)
    }

    suspend fun cameraSlots(cameraId: String): List<CloudSlot> = withContext(Dispatchers.IO) {
        val raw = http.get("$IR_HOST/camera/$guid/$cameraId", bearer, "IF_IR_R_C002")
        parseSlots(raw)
    }

    /**
     * 覆盖相机上的 9 个云创槽位排布（`IF_IR_R_C004`）。
     *
     * 语义来自 Web chunk 的拖拽回调：
     * `l.map((e,s)=> e.id.startsWith("blank") ? null : {slotNo: s+1, recipeId: e.id}).filter(nonNull)`
     * 然后 `PATCH /camera/{guid}/{deviceId}` + `Content-Type: application/json`，body `{recipes:[...]}`。
     */
    suspend fun replaceSlots(cameraId: String, assignments: List<CloudSlotAssignment>) = withContext(Dispatchers.IO) {
        val raw = http.patch(
            "$IR_HOST/camera/$guid/$cameraId",
            buildSlotBody(assignments),
            bearer,
            "IF_IR_R_C004",
            contentType = "application/json",
        )
        // 成功时 Web 端只判断"有返回值"；这里同样不猜结构，非异常即视为提交成功，
        // 真正的排布以随后重新 GET 的结果为准。
        raw
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
        // 真机实测（2026-11-13）：`{"cameras":[{"deviceId":"01JTC8...","name":"Zf","serialNo":"8032632…"}]}`
        // —— 主键叫 deviceId，不是 id/cameraId。这是 C001 的第一份真身，按它校准。
        val arr = http.findObjectArray(element, CAMERA_KEYS, mustHave = "deviceId")
            ?: http.findObjectArray(element, CAMERA_KEYS, mustHave = "id")
            ?: http.findObjectArray(element, CAMERA_KEYS, mustHave = "cameraId")
            ?: http.findObjectArray(element, CAMERA_KEYS, mustHave = "device_id")
            ?: emptyArrayOrFail(element, raw, "IF_IR_R_C001", "相机列表")
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = firstNonBlank(o, "deviceId", "device_id", "id", "cameraId") ?: return@mapNotNull null
            CloudCamera(
                id = id,
                name = firstNonBlank(o, "name", "cameraName", "nickname", "displayName").orEmpty(),
                model = firstNonBlank(o, "model", "modelName", "productName", "cameraModel").orEmpty(),
                serial = firstNonBlank(o, "serialNo", "serial_no", "serialNumber", "serial").orEmpty(),
            )
        }
    }

    fun parseSlots(raw: String): List<CloudSlot> {
        val element = http.parseElement(raw, "IF_IR_R_C002")
        val arr = http.findObjectArray(element, SLOT_KEYS, mustHave = "pictureControlName")
            ?: http.findObjectArray(element, SLOT_KEYS, mustHave = "exist")
            ?: http.findObjectArray(element, SLOT_KEYS, mustHave = "recipeId")
            ?: http.findObjectArray(element, SLOT_KEYS, mustHave = "id")
            ?: emptyArrayOrFail(element, raw, "IF_IR_R_C002", "槽位")
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
        val arr: JsonArray = http.findObjectArray(element, ASSIGNABLE_KEYS, mustHave = "id")
            ?: emptyArrayOrFail(element, raw, "IF_FR100_H13", "可分配方案")
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

    /** PATCH body 构造（纯函数，便于单测）：只发已占用槽位，按 slotNo 升序。 */
    fun buildSlotBody(assignments: List<CloudSlotAssignment>): String = buildJsonObject {
        put(
            "recipes",
            buildJsonArray {
                assignments.filter { it.recipeId.isNotBlank() }
                    .sortedBy { it.slotNo }
                    .forEach { entry ->
                        add(
                            buildJsonObject {
                                put("slotNo", entry.slotNo)
                                put("recipeId", entry.recipeId)
                            },
                        )
                    }
            },
        )
    }.toString()

    fun parseIdentity(raw: String): CloudIdentity {
        val element = http.parseElement(raw, "OIDC_USERINFO")
        val o = element as? JsonObject
            ?: throw CloudException("OIDC_USERINFO 返回的不是对象：${raw.take(160)}", httpStatus = -2)
        val guid = firstNonBlank(o, "guid", "userGuid", "user_guid", "userId", "user_id")
            ?: o.str("sub")?.takeIf { isGuidLike(it) }
            ?: throw CloudException(
                "账号信息里没有账号 ID（guid）：${raw.take(200)}",
                httpStatus = -2,
            )
        return CloudIdentity(
            guid = guid,
            language = firstNonBlank(o, "languageCode", "locale", "preferred_username").orEmpty(),
            country = firstNonBlank(o, "countryCode", "country", "zoneinfo").orEmpty(),
            subject = o.str("sub").orEmpty(),
        )
    }

    /**
     * 找不到对象数组时区分两种情形：
     * 1) 响应本身就是空数组/空列表 → 真的没有数据，返回空；
     * 2) 其它形状 → 结构变了或解析规则不对，抛错并带原始片段，别把「不认识」说成「账号是空的」。
     */
    private fun emptyArrayOrFail(
        element: JsonElement,
        raw: String,
        callId: String,
        what: String,
    ): JsonArray {
        val definitelyEmpty = when (element) {
            is JsonArray -> element.isEmpty()
            is JsonObject -> element.isEmpty() ||
                element.values.all { value ->
                    (value is JsonArray && value.isEmpty()) || (value is JsonObject && value.isEmpty())
                }
            else -> false
        }
        if (definitelyEmpty) return JsonArray(emptyList())
        throw CloudException(
            "$callId 返回的$what 结构无法识别（${raw.length} 字节）：${raw.take(160)}",
            errorCode = "",
            httpStatus = -2,
        )
    }

    private fun firstNonBlank(o: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> o.str(key)?.takeIf { it.isNotBlank() } }

    companion object {
        private val CAMERA_KEYS = listOf("cameraList", "cameras", "camera", "list", "items")
        private val SLOT_KEYS = listOf(
            "recipes", "recipe", "cameraRecipes", "slots", "cameraSlots",
            "pictureControls", "pictureControl", "controlList", "list", "items",
        )
        private val ASSIGNABLE_KEYS = listOf("recipe", "recipes", "list", "items", "cameraRecipes")
    }
}
