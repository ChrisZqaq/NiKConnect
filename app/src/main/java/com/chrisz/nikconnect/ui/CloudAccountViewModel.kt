package com.chrisz.nikconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudAccountClient
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.CloudSlot
import com.chrisz.nikconnect.cloud.guidCandidates
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
    val rawSnippet: String? = null,
    /** 需要用户去网页「相机」页重新捕获账号 ID 时置 true（当前 guid 不被服务端接受）。 */
    val needsGuidRefresh: Boolean = false,
) {
    val hasCredentials: Boolean get() = !guid.isNullOrBlank()
}

internal class CloudAccountViewModel(
    private val token: String,
    guid: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(CloudAccountState(guid = guid))
    val state: StateFlow<CloudAccountState> = _state.asStateFlow()

    /** 服务端接受过的 guid；抓到错的那种（如把方案 ID 当账号 ID）时换成 token 自带的值。 */
    private var workingGuid: String? = null

    init {
        refreshCameras()
    }

    fun refreshCameras() {
        val candidates = guidCandidates(token, workingGuid ?: _state.value.guid)
        if (candidates.isEmpty()) {
            _state.update {
                it.copy(cameraError = "没有拿到账号 guid（登录后自动捕获）。点右上角刷新，或用「重新获取账号 ID」在网页里打开相机页。", needsGuidRefresh = true)
            }
            return
        }
        if (_state.value.loadingCameras) return
        _state.update { it.copy(loadingCameras = true, cameraError = null, rawSnippet = null) }
        viewModelScope.launch {
            var lastError: CloudException? = null
            for (guid in candidates) {
                val client = CloudAccountClient(accessToken = token, guid = guid)
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
                            needsGuidRefresh = false,
                        )
                    }
                    loadAssignableCount(client)
                    return@launch
                } catch (e: CloudException) {
                    lastError = e
                    // 只有「guid 不被接受」这一类才值得换下一个候选；频控/网络问题换 guid 也没用
                    if (e.httpStatus !in GUID_REJECTED_CODES && e.errorCode.isNotBlank()) break
                }
            }
            val error = lastError ?: return@launch
            _state.update {
                it.copy(
                    loadingCameras = false,
                    cameraError = describe(error, "IF_IR_R_C001"),
                    rawSnippet = error.message,
                    needsGuidRefresh = error.httpStatus in GUID_REJECTED_CODES,
                )
            }
        }
    }

    /** 可分配方案数量：失败不影响相机列表展示。 */
    private suspend fun loadAssignableCount(client: CloudAccountClient) {
        try {
            val assignable = client.assignableRecipes()
            _state.update { it.copy(assignableCount = assignable.size) }
        } catch (e: CloudException) {
            _state.update { it.copy(assignableCount = null) }
        }
    }

    fun openCamera(camera: CloudCamera) {
        val guid = workingGuid ?: _state.value.guid ?: return
        _state.update { it.copy(selected = camera, slots = emptyList(), slotsError = null, loadingSlots = true) }
        viewModelScope.launch {
            val client = CloudAccountClient(accessToken = token, guid = guid)
            try {
                val slots = client.cameraSlots(camera.id)
                _state.update {
                    it.copy(
                        slots = slots,
                        loadingSlots = false,
                        slotsError = if (slots.isEmpty()) "相机没有返回槽位数据。" else null,
                    )
                }
            } catch (e: CloudException) {
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

    fun backToList() {
        _state.update { it.copy(selected = null, slots = emptyList(), slotsError = null) }
    }

    private fun describe(e: CloudException, callId: String): String = when {
        e.errorCode.isNotBlank() -> "$callId 返回 ${e.errorCode}"
        e.httpStatus == 401 || e.httpStatus == 403 -> "$callId 认证失败（${e.httpStatus}）：token 可能已过期，重新登录一次"
        e.httpStatus == 422 -> "$callId 拒绝了这个账号 ID（422）。点「重新获取账号 ID」，在网页里打开相机页会自动抓到正确的值。"
        else -> e.message.orEmpty()
    }

    private companion object {
        /** 服务端认为 guid 不合法时的状态码：400 参数错、401/403 认证、422 无法处理。 */
        val GUID_REJECTED_CODES = setOf(400, 401, 403, 422)
    }
}
