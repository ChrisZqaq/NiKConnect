package com.chrisz.nikconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudAccountApi
import com.chrisz.nikconnect.cloud.CloudAccountClient
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.CloudSlotAssignment
import com.chrisz.nikconnect.cloud.mergeSlots
import com.chrisz.nikconnect.cloud.planSlotWrite
import com.chrisz.nikconnect.cloud.OwnedRecipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 详情页「加到相机」的界面状态。 */
internal data class AddToCameraState(
    val loading: Boolean = false,
    val cameras: List<CloudCamera> = emptyList(),
    /** 每台相机的 9 个槽位（按位置排好，空位也在里面）。 */
    val slots: Map<String, List<CloudSlot>> = emptyMap(),
    val error: String? = null,
    /** 链路里的当前步骤，例如「正在加入收藏」。 */
    val working: String? = null,
    val notice: String? = null,
)

/**
 * 详情页「加到相机」：官方方案 → 我的收藏 → 相机可分配池 → 指定槽位。
 *
 * 为什么是四步而不是一步：
 * - `IF_IR_R_LR002` 才有"账号里的一份方案"，官方目录 id 不能直接排进槽位；
 * - `IF_IR_R_C003` 只把方案加进相机的可分配池，不指定位置；
 * - `IF_IR_R_C004`（PATCH）才是排布，而且是**整体覆盖**，所以必须先读一次真实排布，
 *   把原来的其它槽位一起带上，否则会把别人的位置冲掉。
 */
internal class CloudAddToCameraViewModel(
    private val token: String,
    private val refreshToken: String? = null,
    private val capturedGuid: String? = null,
    private val onTokenRefreshed: (String, String?) -> Unit = { _, _ -> },
    private val clientFactory: (String, String) -> CloudAccountApi = { t, g ->
        CloudAccountClient(accessToken = t, guid = g)
    },
) : ViewModel() {

    private val _state = MutableStateFlow(AddToCameraState())
    val state: StateFlow<AddToCameraState> = _state.asStateFlow()

    private var currentToken: String = token
    private var currentRefresh: String? = refreshToken
    private var guid: String? = capturedGuid
    private var refreshTried = false

    fun updateCredentials(newToken: String?, newGuid: String?, newRefresh: String?) {
        newToken?.takeIf { it.isNotBlank() }?.let { currentToken = it }
        newGuid?.takeIf { it.isNotBlank() }?.let { guid = it }
        currentRefresh = newRefresh
        refreshTried = false
    }

    /** 打开抽屉时调用：拿账号 ID、相机列表、每台相机的槽位。 */
    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                withSession { api ->
                    if (guid.isNullOrBlank()) {
                        guid = api.fetchIdentity().guid
                    }
                    val cameras = api.listCameras()
                    val layouts = api.fetchAssignable().layouts
                    val slots = cameras.associate { camera ->
                        camera.id to api.cameraSlots(camera.id, layouts[camera.id])
                    }
                    cameras to slots
                }
            }.onSuccess { (cameras, slots) ->
                _state.update { it.copy(loading = false, cameras = cameras, slots = slots) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = describe(e, "读取相机槽位")) }
            }
        }
    }

    /**
     * 把 [recipeId]（官方目录 id）放到 [camera] 的第 [position] 个槽位（0 基）。
     * [alreadyCollectedId] 非空时跳过 LR002，直接用账号里已有的副本。
     */
    fun addToSlot(
        camera: CloudCamera,
        position: Int,
        recipeId: String,
        recipeName: String,
        alreadyCollectedId: String? = null,
    ) {
        if (_state.value.working != null) return
        _state.update { it.copy(working = "正在准备…", error = null) }
        viewModelScope.launch {
            runCatching {
                withSession { api ->
                    val localId = alreadyCollectedId ?: run {
                        _state.update { it.copy(working = "正在加入收藏…") }
                        api.saveToLibrary(name = recipeName, resourceId = recipeId)
                        // LR002 只回 202，本地 id 要回 H05 按名字取
                        api.fetchMyRecipes().recipes
                            .firstOrNull { it.name.equals(recipeName, ignoreCase = true) }?.id
                            ?: throw CloudException("已提交收藏，但在账号里没找到「$recipeName」")
                    }
                    _state.update { it.copy(working = "正在加入相机可用列表…") }
                    api.addRecipes(camera.id, listOf(localId))
                    _state.update { it.copy(working = "正在写入槽位…") }
                    val layout = api.fetchAssignable().layouts[camera.id]
                    val beforeSlots = mergeSlots(layout, emptyList())
                    val target = position + 1

                    // 已经在目标位：C003 可能已把它放进去了，不必再 PATCH。
                    if (beforeSlots.firstOrNull { it.index == target }?.recipeId == localId) {
                        return@withSession recipeName
                    }

                    // 目标位被别的方案占着：那是**真删除**，必须走 C005；PATCH 只表达"仍在的排布"。
                    val occupant = beforeSlots.firstOrNull { it.index == target }
                        ?.recipeId
                        ?.takeIf { it.isNotBlank() }
                    occupant?.let { api.deleteSlot(camera.id, it) }

                    // 同一方案若已在别的槽位，这次只是"挪位"，PATCH 的排布里只留目标位即可，
                    // 不需要先删再加（早先的删+加是多余且危险的）。
                    val assignmentsAtTarget = buildAssignments(
                        layout = beforeSlots.map { if (it.recipeId == localId) null else it.recipeId },
                        position = position,
                        localId = localId,
                    )
                    try {
                        api.replaceSlots(camera.id, assignmentsAtTarget)
                    } catch (e: CloudException) {
                        // PATCH 失败时把刚删掉的方案加回去，别让相机槽位空着
                        occupant?.let { runCatching { api.addRecipes(camera.id, listOf(it)) } }
                        throw e
                    }
                    recipeName
                }
            }.onSuccess { name ->
                _state.update {
                    it.copy(
                        working = null,
                        notice = "已把「$name」放到第 ${position + 1} 位 · 相机联网后生效",
                    )
                }
                load()
            }.onFailure { e ->
                _state.update { it.copy(working = null, error = describe(e, "加入到相机槽位")) }
            }
        }
    }

    fun consumeNotice() = _state.update { it.copy(notice = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    /** 用服务端真实排布拼 PATCH 请求：保留其它非空槽位，把目标位换成 [localId]。 */
    private fun buildAssignments(
        layout: List<String?>?,
        position: Int,
        localId: String,
    ): List<CloudSlotAssignment> {
        val slots = layout ?: emptyList()
        return (0 until maxOf(slots.size, position + 1)).mapNotNull { index ->
            // 同一个方案只允许出现一次：C003 之后服务端可能已经把它塞进别的空位，
            // 这里统一以用户选的位置为准，别的位若也是它就去掉，避免同一个 recipe 占两格。
            val id = if (index == position) {
                localId
            } else {
                slots.getOrNull(index)?.takeIf { it != localId }
            }
            id?.takeIf { it.isNotBlank() }?.let { CloudSlotAssignment(index + 1, it) }
        }
    }

    private suspend fun <T> withSession(block: suspend (CloudAccountApi) -> T): T {
        return try {
            block(clientFactory(currentToken, guid.orEmpty()))
        } catch (e: CloudException) {
            if (e.httpStatus != 401 && e.httpStatus != 403) throw e
            if (!tryRefresh()) throw e
            block(clientFactory(currentToken, guid.orEmpty()))
        }
    }

    private suspend fun tryRefresh(): Boolean {
        val refresh = currentRefresh
        if (refresh.isNullOrBlank() || refreshTried) return false
        refreshTried = true
        val pair = runCatching {
            clientFactory(currentToken, guid.orEmpty()).refreshAccessToken(refresh)
        }.getOrNull() ?: return false
        currentToken = pair.first
        currentRefresh = pair.second ?: currentRefresh
        onTokenRefreshed(currentToken, currentRefresh)
        return true
    }

    private fun describe(e: Throwable, callId: String): String {
        if (e is CloudException) {
            val code = e.errorCode.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            return "$callId 没有成功：${e.message}$code"
        }
        return "$callId 没有成功：${e.message ?: e::class.simpleName}"
    }
}

/** 判断某个方案在当前排布里占着第几位（返回 1 基槽位号，未占用返回 null）。 */
internal fun slotOf(layout: List<String?>?, recipeId: String): Int? {
    layout ?: return null
    val index = layout.indexOf(recipeId)
    return if (index >= 0) index + 1 else null
}

/** 详情页「加到相机」需要的最小信息：这条方案在账号里是否已有副本。 */
internal fun collectedIdOf(recipes: List<OwnedRecipe>, name: String): String? =
    recipes.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
