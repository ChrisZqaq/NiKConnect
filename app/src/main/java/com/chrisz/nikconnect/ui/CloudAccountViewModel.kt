package com.chrisz.nikconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudAccountClient
import com.chrisz.nikconnect.cloud.CloudCamera
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.CloudSlot
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
) {
    val hasCredentials: Boolean get() = !guid.isNullOrBlank()
}

internal class CloudAccountViewModel(
    private val token: String,
    guid: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(CloudAccountState(guid = guid))
    val state: StateFlow<CloudAccountState> = _state.asStateFlow()

    init {
        refreshCameras()
    }

    fun refreshCameras() {
        val guid = _state.value.guid
        if (guid.isNullOrBlank()) {
            _state.update { it.copy(cameraError = "没有拿到账号 guid（登录后自动捕获）。重新登录一次再试。") }
            return
        }
        if (_state.value.loadingCameras) return
        _state.update { it.copy(loadingCameras = true, cameraError = null) }
        viewModelScope.launch {
            val client = CloudAccountClient(accessToken = token, guid = guid)
            try {
                val cameras = client.listCameras()
                _state.update {
                    it.copy(
                        cameras = cameras,
                        loadingCameras = false,
                        cameraError = if (cameras.isEmpty()) "账号下没有已注册相机。先在云创网页登录并绑定相机，或在相机端打开云创同步。" else null,
                    )
                }
            } catch (e: CloudException) {
                _state.update {
                    it.copy(
                        loadingCameras = false,
                        cameraError = describe(e, "IF_IR_R_C001"),
                        rawSnippet = e.message,
                    )
                }
                return@launch
            }
            // 可分配方案数量：失败不影响相机列表展示
            try {
                val assignable = client.assignableRecipes()
                _state.update { it.copy(assignableCount = assignable.size) }
            } catch (e: CloudException) {
                _state.update { it.copy(assignableCount = null) }
            }
        }
    }

    fun openCamera(camera: CloudCamera) {
        val guid = _state.value.guid ?: return
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
                    it.copy(loadingSlots = false, slotsError = describe(e, "IF_IR_R_C002"), rawSnippet = e.message)
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
        else -> e.message.orEmpty()
    }
}
