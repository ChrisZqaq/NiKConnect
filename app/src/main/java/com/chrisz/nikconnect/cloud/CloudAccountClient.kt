package com.chrisz.nikconnect.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    val recipeId: String? = null,
    val name: String = "",
    val pictureControlName: String = "",
    val modifiedAt: String = "",
) {
    val occupied: Boolean get() = !recipeId.isNullOrBlank() || name.isNotBlank()
}

/**
 * 把槽位补齐到固定 9 格。
 *
 * 真机上 `IF_IR_R_C002` 只返回**已占用**的项（相机上只有 2 个方案就只给 2 条），
 * 界面若照抄就会「没有空槽位可以新增」。空的槽位用 [CloudSlot] 默认值占位，
 * `index` 保持 1..9，界面用它算 `slotNo`。
 */
internal fun padSlots(slots: List<CloudSlot>, count: Int = 9): List<CloudSlot> {
    val sorted = slots.sortedBy { it.index }
    if (sorted.size >= count) return sorted
    val taken = sorted.map { it.index }.toSet()
    return (sorted + (1..count).filterNot { it in taken }.map { CloudSlot(index = it) })
        .sortedBy { it.index }
}

/**
 * 把「真实排布」和「方案详情」合成槽位列表。
 *
 * 真机教训（2026-11-14，用户报「删掉第 1 个后 App 里第 2、3 个前移，但相机上第 1 位空着」）：
 * `IF_IR_R_C002` 的 `localRecipes` **只含已占用的方案，不带槽位号**，照数组顺序编号会把空洞抹掉；
 * 真实排布是 `IF_FR100_H13` 里 `camera[].recipeIds` —— 定长 9 的数组，空位是 `null`。
 *
 * @param recipeIds 定长排布（含 null 表示空位）；为 null 时退回「按 C002 数组顺序编号」的老行为
 * @param local C002 的 `localRecipes` 解析结果，只用来取方案名/代号
 */
internal fun mergeSlots(recipeIds: List<String?>?, local: List<CloudSlot>): List<CloudSlot> {
    val detail = local.mapNotNull { slot -> slot.recipeId?.let { it to slot } }.toMap()
    if (recipeIds.isNullOrEmpty()) {
        val dense = local.sortedBy { it.index }
        return padSlots(
            if (dense.isEmpty()) emptyList()
            else dense.mapIndexed { i, slot -> slot.copy(index = i + 1) },
        )
    }
    val slots = recipeIds.mapIndexed { i, id ->
        if (id.isNullOrBlank()) {
            CloudSlot(index = i + 1)
        } else {
            val d = detail[id]
            CloudSlot(
                index = i + 1,
                recipeId = id,
                name = d?.name.orEmpty(),
                pictureControlName = d?.pictureControlName.orEmpty(),
                modifiedAt = d?.modifiedAt.orEmpty(),
            )
        }
    }
    return padSlots(slots)
}

/**
 * 一次槽位排布提交项。Web 端 PATCH 的 body 就是 `{recipes:[{slotNo,recipeId}]}`，
 * 且**只提交已占用的槽位**（空位靠"不在数组里"表示，不是 `null`）。
 */
internal data class CloudSlotAssignment(
    val slotNo: Int,
    val recipeId: String,
)

/** 自己的书。一个书最多 200 个方案，一个账号最多 10 本（Web 端按钮 disabled 条件）。 */
internal data class CloudBook(
    val id: String,
    val label: String,
    val recipeCount: Int = 0,
) {
    companion object {
        const val MAX_BOOKS = 10
        const val MAX_RECIPES_PER_BOOK = 200
    }
}

/** 「我的方案」总览（`IF_FR100_H05`）。 */
internal data class CloudMyRecipes(
    val books: List<CloudBook> = emptyList(),
    val recipeCount: Int = 0,
    val authority: Int = 0,
    val recipes: List<OwnedRecipe> = emptyList(),
)

/** 账号里的一份方案副本（属于某个收藏夹，或未归类）。 */
internal data class OwnedRecipe(
    val id: String,
    val name: String,
    val pictureControlName: String = "",
    val bookId: String? = null,
)

/**
 * `IF_FR100_H13` 的双重用途：可分配池（`recipe[]`）+ 每台相机的真实槽位排布（`camera[].recipeIds`）。
 * 两个字段在同一次响应里，所以一次请求就够，不必再多跑一趟。
 */
internal data class CloudAssignable(
    val recipes: List<CloudRecipe>,
    val layouts: Map<String, List<String?>> = emptyMap(),
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
) : CloudAccountApi {
    private val bearer = mapOf("Authorization" to "Bearer $accessToken")

    /**
     * 取账号身份。
     *
     * 这是 Web 端拿 `guid` 的原路径（`_app` chunk：`keycloak.loadUserInfo()` 之后
     * `guid = e.guid`），比从请求 URL 里猜 ULID 可靠得多——账号页 422 的根因就是猜。
     */
    /** 用 refresh_token 续 access_token（Keycloak 公共客户端）；换不到返回 null。 */
    override suspend fun refreshAccessToken(refreshToken: String): Pair<String, String?>? =
        withContext(Dispatchers.IO) { http.refreshAccessToken(refreshToken) }

    override suspend fun fetchIdentity(): CloudIdentity = withContext(Dispatchers.IO) {
        val raw = http.get("$AUTH_HOST/protocol/openid-connect/userinfo", bearer, "OIDC_USERINFO")
        parseIdentity(raw)
    }

    override suspend fun listCameras(): List<CloudCamera> = withContext(Dispatchers.IO) {
        val raw = http.get("$IR_HOST/camera/$guid/", bearer, "IF_IR_R_C001")
        parseCameras(raw)
    }

    /**
     * 读槽位。`recipeIds` 来自 H13 的 `camera[].recipeIds`（真实排布，空位为 null），
     * C002 只提供方案详情，两者合成才是「第几位放了什么」。
     */
    override suspend fun cameraSlots(cameraId: String, recipeIds: List<String?>?): List<CloudSlot> =
        withContext(Dispatchers.IO) {
            val raw = http.get("$IR_HOST/camera/$guid/$cameraId", bearer, "IF_IR_R_C002")
            mergeSlots(recipeIds, parseSlots(raw))
        }

    /**
     * 覆盖相机上的 9 个云创槽位排布（`IF_IR_R_C004`）。
     *
     * 语义来自 Web chunk 的拖拽回调：
     * `l.map((e,s)=> e.id.startsWith("blank") ? null : {slotNo: s+1, recipeId: e.id}).filter(nonNull)`
     * 然后 `PATCH /camera/{guid}/{deviceId}` + `Content-Type: application/json`，body `{recipes:[...]}`。
     */
    override suspend fun replaceSlots(cameraId: String, assignments: List<CloudSlotAssignment>) = withContext(Dispatchers.IO) {
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

    /**
     * 删除相机上的单个云创槽位方案（`IF_IR_R_C005`）。
     *
     * Web 端删除是**逐条 DELETE**，不是靠"提交缺省的空数组"——`replaceSlots` 传空数组
     * 不在契约内（Web 端 `if (c.length)` 直接跳过 PATCH），服务端会回 422。
     */
    override suspend fun deleteSlot(cameraId: String, recipeId: String) = withContext(Dispatchers.IO) {
        http.request(
            method = "DELETE",
            url = "$IR_HOST/camera/$guid/$cameraId/$recipeId",
            body = null,
            headers = bearer,
            callId = "IF_IR_R_C005",
        )
    }

    /** 一次拿全：可分配池 + 每台相机的真实槽位排布（同一份 H13 响应）。 */
    override suspend fun fetchAssignable(): CloudAssignable = withContext(Dispatchers.IO) {
        val raw = http.post(
            "$CWP_HOST/bff/recipe/camera",
            """{"country":"$country"}""",
            mapOf("access_token" to accessToken),
            "IF_FR100_H13",
        )
        CloudAssignable(recipes = parseAssignable(raw), layouts = parseLayouts(raw))
    }

    suspend fun assignableRecipes(): List<CloudRecipe> = fetchAssignable().recipes

    // ---- 我的书（收藏官方方案到自己账号的唯一通道） ----

    /**
     * `IF_FR100_H05` = `POST /bff/recipe`：我的方案总览，响应里带 `books[]`。
     *
     * 这是「账号里有什么」的真身，也是收藏的目标容器：一个书最多 200 个方案、
     * 一个账号最多 10 本书（Web 端 `disabled: e.book.length >= 10` 与 `recipeCount >= 200`）。
     */
    override suspend fun fetchMyRecipes(): CloudMyRecipes = withContext(Dispatchers.IO) {
        val raw = http.post(
            "$CWP_HOST/bff/recipe",
            """{"filter":{},"country":"$country","lang_code":"zh-CN"}""",
            mapOf("access_token" to accessToken),
            "IF_FR100_H05",
        )
        parseMyRecipes(raw)
    }

    /**
     * `IF_IR_R_PR001` = `POST /publicrecipe/{guid}`：把官方方案**复制**进自己的书。
     *
     * Web 端：`{method:"POST", headers:{Authorization: Bearer}, body:{bookId, recipeIds}}`，
     * 返回体里的 `recipeIds` 是新建出来的本地方案 ID（与官方方案 ID 不同）。
     */
    override suspend fun favoriteRecipes(bookId: String, recipeIds: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            val raw = http.post(
                "$IR_HOST/publicrecipe/$guid",
                buildFavoriteBody(bookId, recipeIds),
                bearer,
                "IF_IR_R_PR001",
                contentType = "application/json",
            )
            parseFavoriteResult(raw)
        }

    /** `IF_IR_R_PB002` = `POST /publicbook/{guid}`：新建一本书，返回新书 id。 */
    /**
     * 新建收藏夹（`IF_IR_R_PB002`）。
     *
     * Web 端只用 `fetch(url, {method:"POST", headers:{Authorization}, body: JSON字符串})`，
     * 没有显式 Content-Type —— fetch 对字符串 body 默认发 `text/plain;charset=UTF-8`。
     * 所以我们先按 text/plain 发；只有服务端回 400/422 时才补一次 `application/json`。
     */
    override suspend fun createBook(name: String): String = withContext(Dispatchers.IO) {
        val body = buildBookBody(name)
        val raw = try {
            http.post("$IR_HOST/publicbook/$guid", body, bearer, "IF_IR_R_PB002")
        } catch (e: CloudException) {
            if (e.httpStatus != 400 && e.httpStatus != 422) throw e
            http.post(
                "$IR_HOST/publicbook/$guid",
                body,
                bearer,
                "IF_IR_R_PB002",
                contentType = "application/json",
            )
        }
        parseCreatedBookId(raw)
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

    /**
     * 从 H13 的 `camera[]` 读每台相机的 `recipeIds`（真实槽位排布）。
     *
     * Web 端就是这么用它的：`p.find(c => c.deviceId === id).recipeIds.map(...)`，
     * `recipeIds[i]` 为 null 表示第 i+1 位是空的 —— 这是唯一能区分「第 1 位空着」和
     * 「第 1 位放了第 2 个方案」的信息源。
     */
    fun parseLayouts(raw: String): Map<String, List<String?>> {
        val element = http.parseElement(raw, "IF_FR100_H13")
        val cameras = http.findObjectArray(element, CAMERA_KEYS, mustHave = "deviceId")
            ?: return emptyMap()
        return cameras.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = firstNonBlank(o, "deviceId", "device_id", "id", "cameraId") ?: return@mapNotNull null
            val ids = o["recipeIds"] as? JsonArray ?: return@mapNotNull null
            id to ids.map { entry ->
                val text = (entry as? JsonPrimitive)?.contentOrNull
                text?.takeIf { it.isNotBlank() && it != "null" && it != "0" }
            }
        }.toMap()
    }

    /**
     * 解析「我的方案」总览：只取 `books[]` 与方案数，其它字段（filter/cameras/nis）本 App 不用。
     */
    fun parseMyRecipes(raw: String): CloudMyRecipes {
        val element = http.parseElement(raw, "IF_FR100_H05")
        val root = element as? JsonObject
            ?: throw CloudException("IF_FR100_H05 返回的不是对象：${raw.take(160)}", httpStatus = -2)
        val directBooks = (root["books"] as? JsonArray)
            ?: (root["bookList"] as? JsonArray)
            ?: (root["book"] as? JsonArray)
        val booksArray = directBooks
            ?: http.findObjectArray(element, BOOK_KEYS, mustHave = "id")
            ?: JsonArray(emptyList())
        val books = booksArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = firstNonBlank(o, "label", "name", "bookName", "book_name").orEmpty()
            val count = o.int("recipeCount") ?: o.int("recipe_count")
            // 兜底嗅探可能抓到方案数组（元素形如 {"id","name"}）：显式 book 键之外，
            // 必须带书名字段或计数才算书，否则会把方案当收藏夹。
            if (directBooks == null) {
                val bookishLabel = firstNonBlank(o, "label", "bookName", "book_name")
                if (bookishLabel.isNullOrBlank() && count == null) return@mapNotNull null
            }
            CloudBook(id = id, label = label, recipeCount = count ?: 0)
        }
        val recipesArray = (root["allLocalRecipes"] as? JsonArray)
            ?: (root["localRecipes"] as? JsonArray)
            ?: http.findObjectArray(element, RECIPE_KEYS, mustHave = "pictureControlName")
            ?: http.findObjectArray(element, RECIPE_KEYS, mustHave = "id")
            ?: JsonArray(emptyList())
        /** 原始数组长度就是账号里的方案份数（含解析不出名字的条目）。 */
        val rawRecipeCount = recipesArray.size
        val recipes = recipesArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = firstNonBlank(o, "id", "recipeId", "recipe_id")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            OwnedRecipe(
                id = id,
                name = firstNonBlank(o, "name", "label", "recipeName", "recipe_name").orEmpty(),
                pictureControlName = firstNonBlank(o, "pictureControlName", "picture_controll_name").orEmpty(),
                bookId = firstNonBlank(o, "bookId", "book_id", "publicBookId", "public_book_id")
                    ?.takeIf { it.isNotBlank() },
            )
        }.filter { it.name.isNotBlank() || it.pictureControlName.isNotBlank() }
        return CloudMyRecipes(
            books = books,
            recipeCount = rawRecipeCount,
            authority = root.int("authority") ?: 0,
            recipes = recipes,
        )
    }

    fun parseFavoriteResult(raw: String): List<String> {
        val element = http.parseElement(raw, "IF_IR_R_PR001")
        val root = element as? JsonObject ?: return emptyList()
        val ids = (root["recipeIds"] as? JsonArray)
            ?: findStringArray(element, listOf("recipeIds", "recipes", "ids"))
            ?: return emptyList()
        return ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotBlank() } }
    }

    fun parseCreatedBookId(raw: String): String {
        val element = http.parseElement(raw, "IF_IR_R_PB002")
        val o = element as? JsonObject
            ?: throw CloudException("IF_IR_R_PB002 返回的不是对象：${raw.take(160)}", httpStatus = -2)
        return firstNonBlank(o, "id", "bookId", "book_id")
            ?: throw CloudException("新建书没有返回 id：${raw.take(200)}", httpStatus = -2)
    }

    /** PR001 的请求体（纯函数）：只带 bookId 与实际要复制的方案。 */
    fun buildFavoriteBody(bookId: String, recipeIds: List<String>): String = buildJsonObject {
        put("bookId", bookId)
        put(
            "recipeIds",
            buildJsonArray { recipeIds.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) } },
        )
    }.toString()

    fun buildBookBody(name: String): String = buildJsonObject { put("name", name) }.toString()

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

    /** 在任意层级里找第一个「字符串数组」，用于 `recipeIds` 这类纯 ID 列表。 */
    private fun findStringArray(element: JsonElement, keys: List<String>): JsonArray? {
        when (element) {
            is JsonObject -> {
                keys.forEach { key ->
                    val arr = element[key] as? JsonArray
                    if (arr != null && arr.all { it is JsonPrimitive }) return arr
                }
                element.values.forEach { child -> findStringArray(child, keys)?.let { return it } }
            }
            is JsonArray -> element.forEach { child -> findStringArray(child, keys)?.let { return it } }
            else -> Unit
        }
        return null
    }

    private fun firstNonBlank(o: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> o.str(key)?.takeIf { it.isNotBlank() } }

    companion object {
        private val CAMERA_KEYS = listOf("cameraList", "cameras", "camera", "list", "items")
        private val SLOT_KEYS = listOf(
            "localRecipes", "recipes", "recipe", "cameraRecipes", "slots", "cameraSlots",
            "pictureControls", "pictureControl", "controlList", "list", "items",
        )
        private val ASSIGNABLE_KEYS = listOf("recipe", "recipes", "list", "items", "cameraRecipes")
        private val BOOK_KEYS = listOf("books", "bookList", "book")
        private val RECIPE_KEYS = listOf("allLocalRecipes", "localRecipes", "recipes", "recipe", "recipeList")
    }
}

/**
 * 一次排布写回的"该发什么请求"。抽成纯函数是因为这里最容易搞错：
 *
 * - 顺序没变 → 什么都不发（避免无意义的 PATCH）
 * - 全部清空 → **只能逐条 DELETE**（C005）。`{"recipes":[]}` 不在 C004 契约里，
 *   Web 端 `if (c.length)` 会直接跳过 PATCH；我们真机验证过空数组会回 422。
 * - 其它变化 → 一次 PATCH 全量覆盖（空位靠缺席表达）
 */
internal data class CloudSlotWritePlan(
    val noop: Boolean = false,
    /** 需要逐条删除的方案（只在"全部清空"时使用）。 */
    val deletes: List<String> = emptyList(),
    /** 需要 PATCH 的排布；null 表示不发 PATCH。 */
    val assignments: List<CloudSlotAssignment>? = null,
) {
    val callId: String get() = if (assignments == null) "IF_IR_R_C005" else "IF_IR_R_C004"
}

/**
 * `slotNo` 一律取自 [CloudSlot.index]，**不能用「第几个已占用方案」**：真机上删掉第 1 位后
 * 剩下的是第 2、3 位，按顺序重编号会把 B 发成 slotNo 1，服务端直接 400。
 *
 * @param before 服务端当前的槽位（含空位，`index` 即槽位号）
 * @param after  编辑后界面上的槽位（含空位）
 */
/**
 * 把 [list] 里第 [from] 个槽位搬到第 [to] 个位置，并按最终位置重排 1..n 的槽位号。
 *
 * 必须重排 `index`：`CloudSlot.index` 是服务端槽位号（PATCH 的 `slotNo`），
 * 只交换列表顺序不改 `index` 的话，`planSlotWrite` 会认为"前后一致"而不提交。
 */
internal fun moveSlot(list: List<CloudSlot>, from: Int, to: Int): List<CloudSlot> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    val next = list.toMutableList()
    next.add(to, next.removeAt(from))
    return next.mapIndexed { i, slot -> slot.copy(index = i + 1) }
}

internal fun planSlotWrite(before: List<CloudSlot>, after: List<CloudSlot>): CloudSlotWritePlan {
    val beforeKey = before.assignedSlots()
    val afterKey = after.assignedSlots()
    if (beforeKey == afterKey) return CloudSlotWritePlan(noop = true)
    if (afterKey.isEmpty()) return CloudSlotWritePlan(deletes = beforeKey.map { it.second }.distinct())
    return CloudSlotWritePlan(
        assignments = afterKey.map { (slotNo, recipeId) -> CloudSlotAssignment(slotNo, recipeId) },
    )
}

/** 已占用槽位 → `(槽位号, 方案 ID)`，按槽位号升序。 */
private fun List<CloudSlot>.assignedSlots(): List<Pair<Int, String>> =
    filter { !it.recipeId.isNullOrBlank() }
        .sortedBy { it.index }
        .map { it.index to it.recipeId.orEmpty() }
