package com.chrisz.nikconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudAccountClient
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.CloudRecipe
import com.chrisz.nikconnect.cloud.CloudSlotAssignment
import com.chrisz.nikconnect.cloud.guidCandidates
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
    /** 编辑态：非 null 表示正在改排布，值为界面上看到的顺序（与服务端数据分离，取消即丢弃）。 */
    val draft: List<CloudSlot>? = null,
    val saving: Boolean = false,
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
) {
    val hasCredentials: Boolean get() = !guid.isNullOrBlank()
}

internal class CloudAccountViewModel(
    token: String,
    guid: String?,
) : ViewModel() {
    /** token 会被重新登录替换：不能做成构造时不可变的字段，否则同 guid 换号后还在用旧凭据。 */
    private var accessToken: String = token

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
    fun refreshCameras() {
        if (_state.value.loadingCameras) return
        _state.update { it.copy(loadingCameras = true, cameraError = null, rawSnippet = null) }
        viewModelScope.launch {
            val probe = CloudAccountClient(accessToken = accessToken, guid = _state.value.guid.orEmpty())
            var resolved: String? = null
            var identityError: CloudException? = null
            try {
                resolved = probe.fetchIdentity().guid
            } catch (e: CloudException) {
                identityError = e
            }

            // token 失效是确定性结论，别再拿错 guid 去撞 422 把用户绕晕
            if (resolved == null && identityError?.httpStatus in setOf(401, 403)) {
                _state.update {
                    it.copy(
                        loadingCameras = false,
                        cameraError = "登录态已失效（HTTP ${identityError?.httpStatus}）。点右上角退出后重新登录云创。",
                        rawSnippet = identityError?.message,
                        needsGuidRefresh = false,
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
                val client = CloudAccountClient(accessToken = accessToken, guid = guid)
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
            val client = CloudAccountClient(accessToken = accessToken, guid = guid)
            try {
                val slots = client.cameraSlots(camera.id)
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

    /** 重新登录后调用：token 变了就丢掉旧数据重新拉，旧响应因代次变化自动失效。 */
    fun updateCredentials(token: String?, guid: String?) {
        val next = token?.trim().orEmpty()
        val guidChanged = guid != null && guid != _state.value.guid
        if (next.isEmpty() || (next == accessToken && !guidChanged)) return
        accessToken = next
        workingGuid = guid?.takeIf { it.isNotBlank() }
        slotRequestId++
        _state.value = CloudAccountState(guid = guid ?: _state.value.guid)
        refreshCameras()
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
            val client = CloudAccountClient(accessToken = accessToken, guid = guid)
            try {
                val recipes = client.assignableRecipes()
                _state.update {
                    it.copy(assignable = recipes, loadingAssignable = false, assignableCount = recipes.size)
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
        _state.update { it.copy(draft = slots.sortedBy { s -> s.index }, saveError = null, savedNotice = null) }
    }

    fun cancelEdit() {
        _state.update { it.copy(draft = null, saveError = null) }
    }

    /** 长按拖动后提交新位置：把 from 位置的方案移动到 to 位置，其余顺移。 */
    fun moveSlot(from: Int, to: Int) {
        val draft = _state.value.draft ?: return
        if (from !in draft.indices || to !in draft.indices || from == to) return
        val next = draft.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        _state.update { it.copy(draft = next, saveError = null) }
    }

    /** 点击槽位：占用 → 变空；空 → 变回"空"占位（不做填充，选方案是下面的事）。 */
    fun clearSlot(position: Int) {
        val draft = _state.value.draft ?: return
        if (position !in draft.indices) return
        val slot = draft[position]
        if (!slot.occupied) return
        val next = draft.toMutableList()
        next[position] = slot.copy(recipeId = null, name = "", pictureControlName = "", modifiedAt = "")
        _state.update { it.copy(draft = next, saveError = null) }
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

        val before = _state.value.slots.sortedBy { it.index }
            .mapNotNull { it.recipeId?.takeIf { id -> id.isNotBlank() } }
        val after = draft.mapNotNull { it.recipeId?.takeIf { id -> id.isNotBlank() } }
        if (before == after) {
            _state.update { it.copy(draft = null, savedNotice = "排布没有变化") }
            return
        }
        val plan = planSlotWrite(before, after)
        val callId = plan.callId

        _state.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val client = CloudAccountClient(accessToken = accessToken, guid = guid)
            try {
                // 全清空（after 为空）只能用逐条 DELETE：C004 的空数组不在契约里，服务端回 422。
                val assignments = plan.assignments
                if (assignments == null) {
                    plan.deletes.forEach { client.deleteSlot(camera.id, it) }
                } else {
                    client.replaceSlots(camera.id, assignments)
                }
                val refreshed = client.cameraSlots(camera.id)
                _state.update {
                    it.copy(
                        slots = refreshed,
                        draft = null,
                        saving = false,
                        savedNotice = if (plan.assignments == null) {
                            "已删除 ${plan.deletes.size} 个槽位方案"
                        } else {
                            "已提交 ${plan.assignments.size} 个槽位"
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
                            "请求体：${runCatching { client.buildSlotBody(plan.assignments) }.getOrDefault("?")}\n${e.message}"
                        },
                    )
                }
            }
        }
    }

    fun consumeNotice() {
        _state.update { it.copy(savedNotice = null) }
    }

    fun backToList() {
        slotRequestId++
        _state.update { it.copy(selected = null, slots = emptyList(), slotsError = null, loadingSlots = false) }
    }

    private fun loadAssignableCount(client: CloudAccountClient) {
        viewModelScope.launch {
            runCatching { client.assignableRecipes().size }
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
