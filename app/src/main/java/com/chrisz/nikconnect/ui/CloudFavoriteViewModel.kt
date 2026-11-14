package com.chrisz.nikconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudAccountApi
import com.chrisz.nikconnect.cloud.CloudAccountClient
import com.chrisz.nikconnect.cloud.CloudException
import com.chrisz.nikconnect.cloud.OwnedRecipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页收藏按钮的状态。
 *
 * 语义（真机实测）：
 * - 收藏 = `IF_IR_R_LR002` `POST /localrecipe/{guid}` `{name, createdBy:2, resourceId}` → 202
 * - 取消收藏 = `IF_IR_R_LR003` `PUT /localrecipe/{guid}` `{recipeIds:[本地id]}` → 200
 *   （这条 PUT **是删除**，不是替换整库；网页端删除流程按 100 一批调它）
 * - 「是否已收藏」只能按名字比对：H05 的 `localRecipes` 没有指回源公共方案的字段
 */
internal data class CloudFavoriteState(
    val collected: OwnedRecipe? = null,
    /** 这个方案当前占着哪几台相机的哪几个槽位；取消收藏会把这些槽位一起清掉。 */
    val placements: List<Placement> = emptyList(),
    val loading: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val detail: String? = null,
)

/** 方案在相机槽位里的落点（1 基槽位号）。 */
internal data class Placement(val cameraName: String, val slot: Int)

internal class CloudFavoriteViewModel(
    token: String,
    guid: String?,
    private val refreshToken: String? = null,
    private val onTokenRefreshed: (String, String?) -> Unit = { _, _ -> },
    private val apiFactory: (token: String, guid: String) -> CloudAccountApi =
        { t, g -> CloudAccountClient(accessToken = t, guid = g) },
) : ViewModel() {
    private var accessToken: String = token
    private var currentRefresh: String? = refreshToken
    private var refreshAttempted = false
    private var accountGuid: String = guid.orEmpty()
    private var knownName: String = ""

    private val _state = MutableStateFlow(CloudFavoriteState())
    val state: StateFlow<CloudFavoriteState> = _state.asStateFlow()

    fun updateCredentials(token: String?, guid: String?) {
        val nextToken = token?.trim().orEmpty()
        val nextGuid = guid.orEmpty()
        if (nextToken.isEmpty()) return
        if (nextToken == accessToken && nextGuid == accountGuid) return
        accessToken = nextToken
        accountGuid = nextGuid
        refreshAttempted = false
        _state.value = CloudFavoriteState()
        if (knownName.isNotBlank()) loadCollection(knownName)
    }

    /** 打开详情页时查一次「这个方案在不在我的收藏里」。 */
    fun loadCollection(recipeName: String) {
        if (recipeName.isBlank() || accountGuid.isBlank()) return
        knownName = recipeName
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, detail = null) }
        viewModelScope.launch {
            try {
                ensureGuid()
                val mine = withSession { api().fetchMyRecipes() }
                val hit = mine.recipes.firstOrNull { it.name.equals(recipeName, ignoreCase = true) }
                val placements = if (hit == null) emptyList() else runCatching { loadPlacements(hit.id) }
                    .getOrDefault(emptyList())
                _state.update { it.copy(loading = false, collected = hit, placements = placements) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describe(e, "IF_FR100_H05"), detail = detailOf(e)) }
            }
        }
    }

    /** 未收藏 → 收藏；已收藏时由 UI 先弹二次确认，再调 [removeFromCollection]。 */
    fun toggleCollection(recipeId: String, recipeName: String) {
        if (recipeId.isBlank() || _state.value.working) return
        if (_state.value.collected != null) return
        _state.update { it.copy(working = true, error = null, detail = null) }
        viewModelScope.launch {
            try {
                ensureGuid()
                withSession {
                    api().saveToLibrary(
                        name = recipeName.ifBlank { "NiKConnect" },
                        resourceId = recipeId,
                    )
                }
                val mine = runCatching { withSession { api().fetchMyRecipes() } }.getOrNull()
                val hit = mine?.recipes?.firstOrNull { it.name.equals(recipeName, ignoreCase = true) }
                _state.update {
                    it.copy(
                        working = false,
                        collected = hit,
                        placements = emptyList(),
                        notice = "已收藏，可在账号页槽位「＋」里选它",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(working = false, error = describe(e, "IF_IR_R_LR002"), detail = detailOf(e))
                }
            }
        }
    }

    /** 取消收藏（`IF_IR_R_LR003`，传本地 id）。 */
    fun removeFromCollection(localId: String) {
        if (localId.isBlank() || _state.value.working) return
        _state.update { it.copy(working = true, error = null, detail = null) }
        viewModelScope.launch {
            try {
                ensureGuid()
                withSession { api().removeFromLibrary(listOf(localId)) }
                val cleared = _state.value.placements
                val suffix = if (cleared.isEmpty()) "" else
                    "，同时清空了 " + cleared.joinToString("、") { "${it.cameraName} 第 ${it.slot} 位" } + " 的槽位"
                _state.update {
                    it.copy(
                        working = false,
                        collected = null,
                        placements = emptyList(),
                        notice = "已取消收藏$suffix",
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(working = false, error = describe(e, "IF_IR_R_LR003"), detail = detailOf(e))
                }
            }
        }
    }

    /**
     * 查这个本地方案占着哪些槽位。
     *
     * 为什么需要：真机实测发现**取消收藏会把对应槽位一起清掉**（槽位引用的是本地方案 id），
     * 用户并不知道这件事，所以必须在按钮旁与确认框里说出来。
     * 只用 `H13` 的 `camera[].recipeIds` 就能定位，不必逐台再拉 `C002`。
     */
    private suspend fun loadPlacements(localId: String): List<Placement> {
        if (localId.isBlank()) return emptyList()
        val cameras = withSession { api().listCameras() }
        val assignable = withSession { api().fetchAssignable() }
        return cameras.mapNotNull { camera ->
            val layout = assignable.layouts[camera.id] ?: return@mapNotNull null
            val slot = layout.indexOfFirst { it == localId }
            if (slot < 0) null else Placement(camera.name, slot + 1)
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null, detail = null) }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * access_token 只有 5 分钟（`expires_in=300`），收藏往往在过期后才被点到。
     * 401/403 先拿 refresh_token 换新，换到了就重试一次，换不到才报错。
     */
    private suspend fun <T> withSession(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CloudException) {
            if (e.httpStatus != 401 && e.httpStatus != 403) throw e
            if (!tryRefresh()) throw e
            return block()
        }
    }

    private suspend fun tryRefresh(): Boolean {
        val refresh = currentRefresh?.takeIf { it.isNotBlank() } ?: return false
        if (refreshAttempted) return false
        refreshAttempted = true
        val next = runCatching { api().refreshAccessToken(refresh) }.getOrNull() ?: return false
        val (token, newRefresh) = next
        if (token.isBlank()) return false
        accessToken = token
        currentRefresh = newRefresh ?: refresh
        onTokenRefreshed(token, currentRefresh)
        return true
    }

    private suspend fun ensureGuid() {
        // 登录时抓到的 guid 可能是从 URL 里猜的（方案 ID / bookId 都是 26 位 ULID，形态一样），
        // 拿它拼 /localrecipe/ 会 422，所以这里问一次 OIDC userinfo 拿权威值。
        val identity = runCatching { withSession { api().fetchIdentity() } }.getOrNull()
        if (identity != null && identity.guid.isNotBlank()) accountGuid = identity.guid
    }

    private fun detailOf(e: Exception): String? {
        val raw = e.message.orEmpty()
        return raw.takeIf { it.isNotBlank() }?.let { "详情：$it" }
    }

    private fun api(): CloudAccountApi = apiFactory(accessToken, accountGuid)

    private fun describe(e: Throwable, callId: String): String = when {
        e is CloudException && e.errorCode.isNotBlank() -> "$callId 返回 ${e.errorCode}"
        e is CloudException && (e.httpStatus == 401 || e.httpStatus == 403) ->
            "$callId 认证失败（${e.httpStatus}）：重新登录一次云创"
        else -> "$callId：${e.message.orEmpty().ifBlank { e::class.simpleName.orEmpty() }}"
    }
}
