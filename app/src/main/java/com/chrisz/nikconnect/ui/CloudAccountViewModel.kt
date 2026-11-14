package com.chrisz.nikconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudAccountApi
import com.chrisz.nikconnect.cloud.CloudAccountClient
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.CloudMyRecipes
import com.chrisz.nikconnect.cloud.CloudRecipe
import com.chrisz.nikconnect.cloud.OwnedRecipe
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.CloudSlotAssignment
import com.chrisz.nikconnect.cloud.guidCandidates
import com.chrisz.nikconnect.cloud.moveSlot
import com.chrisz.nikconnect.cloud.padSlots
import com.chrisz.nikconnect.cloud.planSlotWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 云创账号页状态（0.6 批次 1：只读核对）。
 *
 * 相机列表与 9 个槽位的响应形状尚未在真机核验，所以解析失败时把原始响应留在 [rawSnippet]，
 * 让用户能截图反馈，而不是只给一句"加载失败"。
 */
internal data class CloudAccountState(
    val guid: String? = null,
    val cameras: List<CloudCamera> = emptyList(),
    val loadingCameras: Boolean = false,
    val cameraError: String? = null,
    val selected: CloudCamera? = null,
    val slots: List<CloudSlot> = emptyList(),
    val loadingSlots: Boolean = false,
    val slotsError: String? = null,
    val assignableCount: Int? = null,
    /** 账号里的收藏夹与方案副本（`IF_FR100_H05`）。 */
    val library: CloudMyRecipes? = null,
    val loadingLibrary: Boolean = false,
    val libraryError: String? = null,
    /** 编辑态：非 null 表示正在改排布，值为界面上看到的顺序（与服务端数据分离，取消即丢弃）。 */
    val draft: List<CloudSlot>? = null,
    val saving: Boolean = false,
    /** 正在删除的槽位方案 ID（非 null 时该行显示进度、按钮禁用）。 */
    val deleting: String? = null,
    val saveError: String? = null,
    /** 保存成功的瞬时提示（UI 消费后置空）。 */
    val savedNotice: String? = null,
    /** 账号可分配池（`IF_FR100_H13`）：空槽位只能从这里挑，不能凭空塞。 */
    val assignable: List<CloudRecipe> = emptyList(),
    val loadingAssignable: Boolean = false,
    val assignableError: String? = null,
    /** 正在为哪个槽位挑方案（null = 选择器关闭）。 */
    val pickingSlot: Int? = null,
    val rawSnippet: String? = null,
    /** 需要用户去网页「相机」页重新捕获账号 ID 时置 true（当前 guid 不被服务端接受）。 */
    val needsGuidRefresh: Boolean = false,
    /** 登录态已过期（userinfo 401/403）：需要用户重新登录，不是账号 ID 的问题。 */
    val sessionExpired: Boolean = false,
) {
    val hasCredentials: Boolean get() = !guid.isNullOrBlank()
}

internal class CloudAccountViewModel(
    token: String,
    guid: String?,
    refreshToken: String? = null,
    /** 自动续期成功时把新凭据交回上层（MainViewModel 会写进 Keystore）。 */
    private val onTokenRefreshed: (CloudAuthState) -> Unit = {},
    /** 测试注入点：纯 JVM 测试用它塞假客户端，避免连网。 */
    private val clientFactory: (String, String) -> CloudAccountApi = { token, guid ->
        CloudAccountClient(accessToken = token, guid = guid)
    },
) : ViewModel() {
    /** token 会被重新登录替换：不能做成构造时不可变的字段，否则同 guid 换号后还在用旧凭据。 */
    private var accessToken: String = token

    /** refresh_token 只在本 VM 内用于续期，不参与任何请求头。 */
    private var currentRefreshToken: String? = refreshToken

    /** 401 只自动续一次，避免在 refresh_token 也失效时无限重试。 */
    private var refreshAttempted = false

    /** 槽位请求代次：切换相机/返回列表/重登都会让旧响应失效。 */
    private var slotRequestId = 0

    private val _state = MutableStateFlow(CloudAccountState(guid = guid))
    val state: StateFlow<CloudAccountState> = _state.asStateFlow()

    /** 服务端接受过的 guid；抓到错的那种（如把方案 ID 当账号 ID）时换成 token 自带的值。 */
    private var workingGuid: String? = null

    init {
        refreshCameras()
    }

    /**
     * 先向账号中心（Keycloak userinfo）问「我是谁」，再拿这个 guid 去查相机。
     *
     * 0.6.0/0.6.1 的 422 根因就在这：Web 端的 guid 不是从 URL 里猜出来的，而是
     * `keycloak.loadUserInfo()` 返回的 `guid`。从 URL 嗅探 ULID 会抓到方案 ID／bookId，
     * 带错 guid 请求 `IF_IR_R_C001` 时网关返回 422。
     */
    fun refreshCameras(force: Boolean = false) {
        if (_state.value.loadingCameras && !force) return
        _state.update { it.copy(loadingCameras = true, cameraError = null, rawSnippet = null) }
        viewModelScope.launch {
            val probe = clientFactory(accessToken, _state.value.guid.orEmpty())
            var resolved: String? = null
            var identityError: CloudException? = null
            try {
                resolved = probe.fetchIdentity().guid
            } catch (e: CloudException) {
                identityError = e
            }

            // 401/403 先尝试用 refresh_token 静默续期，续不上再让用户重登
            if (resolved == null && identityError?.httpStatus in setOf(401, 403) && !refreshAttempted) {
                refreshAttempted = true
                if (tryRefreshToken()) {
                    refreshCameras(force = true)
                    return@launch
                }
            }

            // token 失效是确定性结论，别再拿错 guid 去撞 422 把用户绕晕
            if (resolved == null && identityError?.httpStatus in setOf(401, 403)) {
                _state.update {
                    it.copy(
                        loadingCameras = false,
                        cameraError = "登录态已失效（HTTP ${identityError?.httpStatus}）。点下面的「重新登录云创」即可，不用退出账号。",
                        rawSnippet = identityError?.message,
                        needsGuidRefresh = false,
                        sessionExpired = true,
                    )
                }
                return@launch
            }

            val candidates = if (resolved != null) {
                listOf(resolved)
            } else {
                guidCandidates(accessToken, workingGuid ?: _state.value.guid)
            }
            if (candidates.isEmpty()) {
                _state.update {
                    it.copy(
                        guid = resolved,
                        loadingCameras = false,
                        cameraError = identityError?.message
                            ?: "没有拿到账号 ID。点右上角退出后重新登录云创。",
                        rawSnippet = identityError?.message,
                        needsGuidRefresh = true,
                    )
                }
                return@launch
            }

            var lastError: CloudException? = null
            for (guid in candidates) {
                val client = clientFactory(accessToken, guid)
                try {
                    val cameras = client.listCameras()
                    workingGuid = guid
                    _state.update {
                        it.copy(
                            guid = guid,
                            cameras = cameras,
                            loadingCameras = false,
                            cameraError = if (cameras.isEmpty()) {
                                "账号下没有已注册相机。先在云创网页绑定相机，或让相机联网做一次云创同步。"
                            } else {
                                null
                            },
                            rawSnippet = identityError?.message,
                            needsGuidRefresh = false,
                        )
                    }
                    loadAssignableCount(client)
                    loadLibrary(client)
                    return@launch
                } catch (e: CloudException) {
                    lastError = e
                }
            }
            _state.update {
                it.copy(
                    guid = resolved ?: it.guid,
                    loadingCameras = false,
                    cameraError = describe(lastError, "IF_IR_R_C001"),
                    rawSnippet = lastError?.message,
                    needsGuidRefresh = lastError?.httpStatus in GUID_REJECTED_CODES,
                )
            }
        }
    }

    fun openCamera(camera: CloudCamera) {
        val guid = workingGuid ?: _state.value.guid ?: return
        loadAssignable(guid)
        val requestId = ++slotRequestId
        _state.update { it.copy(selected = camera, slots = emptyList(), slotsError = null, loadingSlots = true) }
        viewModelScope.launch {
            val client = clientFactory(accessToken, guid)
            try {
                val slots = fetchSlots(client, camera.id)
                // 迟到结果保护：期间切了相机/返回列表/换了账号，就丢弃这次响应
                if (requestId != slotRequestId || _state.value.selected?.id != camera.id) return@launch
                _state.update {
                    it.copy(
                        slots = slots,
                        loadingSlots = false,
                        slotsError = if (slots.isEmpty()) "相机没有返回槽位数据。" else null,
                    )
                }
            } catch (e: CloudException) {
                if (requestId != slotRequestId) return@launch
                _state.update {
                    it.copy(
                        loadingSlots = false,
                        slotsError = describe(e, "IF_IR_R_C002"),
                        rawSnippet = e.message,
                        needsGuidRefresh = it.needsGuidRefresh || e.httpStatus in GUID_REJECTED_CODES,
                    )
                }
            }
        }
    }

    /** 用 refresh_token 换新 access_token；成功则回调上层持久化。 */
    private suspend fun tryRefreshToken(): Boolean {
        val refresh = currentRefreshToken?.takeIf { it.isNotBlank() } ?: return false
        val client = clientFactory(accessToken, _state.value.guid.orEmpty())
        val renewed = runCatching { client.refreshAccessToken(refresh) }.getOrNull() ?: return false
        accessToken = renewed.first
        currentRefreshToken = renewed.second ?: refresh
        _state.update { it.copy(sessionExpired = false, cameraError = null) }
        onTokenRefreshed(
            CloudAuthState(
                token = accessToken,
                guid = _state.value.guid,
                refreshToken = currentRefreshToken,
            ),
        )
        return true
    }

    /**
     * 带续期的调用：401/403 时先用 refresh_token 换新 token，再重试一次。
     *
     * access_token 只有 5 分钟（`expires_in=300`），槽位页停留一会儿就会 401。
     * 之前只有"读账号信息"这一条路会续期，C002/C005/C004/H05 全都直接失败。
     */
    private suspend fun <T> withSession(block: suspend (CloudAccountApi) -> T): T {
        fun client() = clientFactory(accessToken, workingGuid ?: _state.value.guid.orEmpty())
        return try {
            block(client())
        } catch (e: CloudException) {
            if (e.httpStatus in setOf(401, 403) && tryRefreshToken()) block(client()) else throw e
        }
    }

    /** 重新登录后调用：token 变了就丢掉旧数据重新拉，旧响应因代次变化自动失效。 */
    fun updateCredentials(token: String?, guid: String?, refreshToken: String? = null) {
        val next = token?.trim().orEmpty()
        val guidChanged = guid != null && guid != _state.value.guid
        if (next.isEmpty() || (next == accessToken && !guidChanged)) return
        accessToken = next
        refreshToken?.takeIf { it.isNotBlank() }?.let { currentRefreshToken = it }
        refreshAttempted = false
        workingGuid = guid?.takeIf { it.isNotBlank() }
        slotRequestId++
        _state.value = CloudAccountState(guid = guid ?: _state.value.guid)
        refreshCameras()
    }

    // ---- 收藏夹与方案副本（IF_FR100_H05） ----

    /**
     * 拉账号里的收藏夹。只为"看清楚我有哪些方案、分别是几份"，不做写操作：
     * 服务端没有 `DELETE /publicbook`，删夹只能去网页端，所以这里只读。
     */
    private fun loadLibrary(client: CloudAccountApi) {
        if (_state.value.loadingLibrary) return
        _state.update { it.copy(loadingLibrary = true, libraryError = null) }
        viewModelScope.launch {
            try {
                val library = withSession { it.fetchMyRecipes() }
                _state.update { it.copy(library = library, loadingLibrary = false) }
            } catch (e: CloudException) {
                _state.update {
                    it.copy(loadingLibrary = false, libraryError = describe(e, "IF_FR100_H05"))
                }
            }
        }
    }

    // ---- 可分配方案池（IF_FR100_H13） ----

    /**
     * 拉"账号可分配方案"。它决定空槽位能放什么：只能放这个池里的方案。
     * 池为空通常意味着账号书里还没有方案——需要先在云创网页收藏，App 不代做。
     */
    fun loadAssignable(explicitGuid: String? = null) {
        val guid = explicitGuid ?: workingGuid ?: _state.value.guid ?: return
        if (_state.value.loadingAssignable) return
        _state.update { it.copy(loadingAssignable = true, assignableError = null) }
        viewModelScope.launch {
            val client = clientFactory(accessToken, guid)
            try {
                val assignable = withSession { it.fetchAssignable() }
                _state.update {
                    it.copy(
                        assignable = assignable.recipes,
                        loadingAssignable = false,
                        assignableCount = assignable.recipes.size,
                    )
                }
            } catch (e: CloudException) {
                _state.update {
                    it.copy(
                        loadingAssignable = false,
                        assignableError = describe(e, "IF_FR100_H13"),
                        rawSnippet = e.message,
                    )
                }
            }
        }
    }

    fun openSlotPicker(position: Int) {
        if (_state.value.draft == null) return
        _state.update { it.copy(pickingSlot = position) }
        if (_state.value.assignable.isEmpty()) loadAssignable()
    }

    /**
     * 从「账号里的方案」直接放进槽位。
     *
     * 删空槽位后相机可分配池会被清空（H13 `recipe: []`），此时选择器没有可选项。
     * 这里先 `IF_IR_R_C003` 把方案加回相机的池子，再刷新排布、再落到 draft 上。
     */
    fun fillSlotFromLibrary(position: Int, recipe: OwnedRecipe) {
        val camera = _state.value.selected ?: return
        val draft = _state.value.draft ?: return
        if (position !in draft.indices) return
        viewModelScope.launch {
            _state.update { it.copy(loadingAssignable = true, saveError = null) }
            try {
                val client = clientFactory(accessToken, _state.value.guid.orEmpty())
                client.addRecipes(camera.id, listOf(recipe.id))
                val assignable = runCatching { withSession { it.fetchAssignable() } }.getOrNull()
                val slots = padSlots(
                    client.cameraSlots(camera.id, assignable?.layouts?.get(camera.id)),
                )
                _state.update { current ->
                    val currentDraft = current.draft ?: return@update current
                    if (position !in currentDraft.indices) return@update current
                    val next = currentDraft.toMutableList()
                    next[position] = CloudSlot(
                        index = next[position].index,
                        recipeId = recipe.id,
                        name = recipe.name,
                        pictureControlName = recipe.pictureControlName,
                        modifiedAt = "",
                    )
                    current.copy(
                        assignable = assignable?.recipes ?: current.assignable,
                        loadingAssignable = false,
                        slots = slots,
                        draft = next,
                        pickingSlot = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loadingAssignable = false,
                        saveError = describe(e as? CloudException, "IF_IR_R_C003"),
                        rawSnippet = e.message,
                    )
                }
            }
        }
    }

    fun dismissSlotPicker() {
        _state.update { it.copy(pickingSlot = null) }
    }

    /** 把一个可分配方案放进指定槽位（只改 draft，仍需「保存到相机」才写回）。 */
    fun fillSlot(position: Int, recipe: CloudRecipe) {
        val draft = _state.value.draft ?: return
        if (position !in draft.indices) return
        val next = draft.toMutableList()
        next[position] = CloudSlot(
            index = next[position].index,
            recipeId = recipe.id,
            name = recipe.name,
            pictureControlName = recipe.pictureControlName,
            modifiedAt = "",
        )
        _state.update { it.copy(draft = next, pickingSlot = null, saveError = null) }
    }

    // ---- 排布编辑（0.6 批次 2：IF_IR_R_C004 写入） ----

    fun beginEdit() {
        val slots = _state.value.slots
        if (slots.isEmpty()) return
        _state.update {
            it.copy(draft = padSlots(slots), saveError = null, savedNotice = null)
        }
    }

    fun cancelEdit() {
        _state.update { it.copy(draft = null, saveError = null) }
    }

    /** 长按拖动后提交新位置：把 from 位置的方案移动到 to 位置，其余顺移。 */
    fun moveSlot(from: Int, to: Int) {
        val draft = _state.value.draft ?: return
        val next = moveSlot(draft, from, to)
        if (next === draft) return
        _state.update { it.copy(draft = next, saveError = null) }
    }

    /**
     * 删除单个槽位方案：直接 `DELETE /camera/{guid}/{deviceId}/{recipeId}`（IF_IR_R_C005）。
     *
     * 不做"先标空、再 PATCH 缺省"——真机验证过 PATCH 缺省不成立（服务端 400），
     * Web 端删除也是逐条 DELETE。删完立即重新 GET，用服务端结果覆盖本地。
     */
    fun deleteSlot(recipeId: String) {
        val camera = _state.value.selected ?: return
        if (recipeId.isBlank() || _state.value.deleting != null) return
        val guid = workingGuid ?: _state.value.guid ?: return
        _state.update { it.copy(deleting = recipeId, saveError = null, rawSnippet = null) }
        val requestId = ++slotRequestId
        viewModelScope.launch {
            val client = clientFactory(accessToken, guid)
            try {
                withSession { it.deleteSlot(camera.id, recipeId) }
                val refreshed = fetchSlots(client, camera.id)
                if (requestId != slotRequestId || _state.value.selected?.id != camera.id) return@launch
                _state.update {
                    it.copy(
                        slots = refreshed,
                        draft = null,
                        deleting = null,
                        savedNotice = "已删除该槽位方案 · 相机联网后生效",
                    )
                }
            } catch (e: CloudException) {
                if (requestId != slotRequestId) return@launch
                _state.update {
                    it.copy(
                        deleting = null,
                        saveError = describe(e, "IF_IR_R_C005"),
                        rawSnippet = "DELETE /camera/{guid}/${camera.id}/$recipeId\n${e.message}",
                    )
                }
            }
        }
    }

    /**
     * 提交排布：只把**已占用**的槽位按 `{slotNo, recipeId}` 发出去（空位靠缺席表达）。
     * 成功后重新 GET 一次，以服务端结果为准，而不是相信本地 draft。
     */
    fun saveLayout() {
        val camera = _state.value.selected ?: return
        val draft = _state.value.draft ?: return
        if (_state.value.saving) return
        val guid = workingGuid ?: _state.value.guid ?: return

        val before = padSlots(_state.value.slots)
        val after = padSlots(draft)
        val plan = planSlotWrite(before, after)
        if (plan.noop) {
            _state.update { it.copy(draft = null, savedNotice = "排布没有变化") }
            return
        }
        val callId = plan.callId

        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val client = clientFactory(accessToken, guid)
            try {
                // 删除永远逐条走 C005：C004 的空数组不在契约里（服务端 422），
                // 而"替换已占用的位置"也必须先删旧的再 PATCH（真机 400 实证）。
                plan.deletes.forEach { id -> withSession { it.deleteSlot(camera.id, id) } }
                plan.assignments?.let { assignments ->
                    withSession { it.replaceSlots(camera.id, assignments) }
                }
                val refreshed = fetchSlots(client, camera.id)
                _state.update {
                    it.copy(
                        slots = refreshed,
                        draft = null,
                        saving = false,
                        savedNotice = if (plan.assignments == null) {
                            "已删除 ${plan.deletes.size} 个槽位方案 · 相机联网后生效"
                        } else {
                            "已保存 ${plan.assignments.size} 个槽位的排布 · 相机联网后生效"
                        },
                    )
                }
            } catch (e: CloudException) {
                _state.update {
                    it.copy(
                        saving = false,
                        saveError = describe(e, callId),
                        rawSnippet = if (plan.assignments == null) {
                            "DELETE ${plan.deletes.size} 个方案\n${e.message}"
                        } else {
                            "请求体：${runCatching { CloudAccountClient(accessToken = accessToken, guid = _state.value.guid.orEmpty()).buildSlotBody(plan.assignments) }.getOrDefault("?")}\n${e.message}"
                        },
                    )
                }
            }
        }
    }

    fun consumeNotice() {
        _state.update { it.copy(savedNotice = null) }
    }

    /**
     * 重新拉一次「真实排布 + 方案详情」，顺便刷新可分配池。
     *
     * 槽位号必须以 H13 的 `recipeIds` 为准：C002 的 `localRecipes` 只有已占用项、不带槽位号，
     * 用它按数组顺序编号会把空洞抹平（真机：删掉第 1 位后 App 显示 2、3 前移，相机上第 1 位空着），
     * 之后 PATCH 的 `slotNo` 全部错位，服务端 400。
     */
    private suspend fun fetchSlots(
        client: CloudAccountApi,
        cameraId: String,
    ): List<CloudSlot> {
        val assignable = runCatching { client.fetchAssignable() }.getOrNull()
        if (assignable != null) {
            _state.update {
                it.copy(
                    assignable = assignable.recipes,
                    assignableCount = assignable.recipes.size,
                    assignableError = null,
                )
            }
        }
        return withSession { it.cameraSlots(cameraId, assignable?.layouts?.get(cameraId)) }
    }

    fun backToList() {
        slotRequestId++
        _state.update { it.copy(selected = null, slots = emptyList(), slotsError = null, loadingSlots = false) }
    }

    private fun loadAssignableCount(client: CloudAccountApi) {
        viewModelScope.launch {
            runCatching { client.fetchAssignable().recipes.size }
                .onSuccess { count -> _state.update { it.copy(assignableCount = count) } }
        }
    }

    private fun describe(e: CloudException?, callId: String): String = if (e == null) {
        "$callId 未执行"
    } else when {
        e.errorCode.isNotBlank() -> "$callId 返回 ${e.errorCode}"
        e.httpStatus == 401 || e.httpStatus == 403 -> "$callId 认证失败（${e.httpStatus}）：token 可能已过期，重新登录一次"
        // 422 不等于「账号 ID 错」：空数组、缺字段、不含法参数都可能回 422。
        // 这里只如实给出服务端原文，不替它下结论。
        e.httpStatus == 422 -> "$callId 被服务端拒绝（422）：${e.message.orEmpty()}"
        else -> e.message.orEmpty()
    }

    private companion object {

        /** 服务端认为 guid 不合法时的状态码：400 参数错、401/403 认证、422 无法处理。 */
        val GUID_REJECTED_CODES = setOf(400, 401, 403, 422)
    }
}
