package com.chrisz.nikconnect.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudAccountClient
import com.chrisz.nikconnect.cloud.CloudBook
import com.chrisz.nikconnect.cloud.CloudException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「收藏」的状态。
 *
 * 这一层存在的理由：官方 144 个方案不在账号里，只能看到、不能分配；要让它进
 * `IF_FR100_H13` 的可分配池（也就是能排进相机 9 个槽位），必须先
 * `POST /publicrecipe/{guid}` 把它**复制**进自己的一个收藏夹。这是本 App 第一处真正改账号数据的操作。
 */
internal data class CloudFavoriteState(
    val books: List<CloudBook> = emptyList(),
    val recipeCount: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val working: Boolean = false,
    val phase: String? = null,
    val notice: String? = null,
    val detail: String? = null,
) {
    val canCreateBook: Boolean get() = books.size < CloudBook.MAX_BOOKS
}

internal class CloudFavoriteViewModel(
    token: String,
    guid: String?,
) : ViewModel() {
    private var accessToken: String = token
    private var accountGuid: String = guid.orEmpty()

    private val _state = MutableStateFlow(CloudFavoriteState())
    val state: StateFlow<CloudFavoriteState> = _state.asStateFlow()

    fun updateCredentials(token: String?, guid: String?) {
        val nextToken = token?.trim().orEmpty()
        val nextGuid = guid.orEmpty()
        if (nextToken.isEmpty()) return
        if (nextToken == accessToken && nextGuid == accountGuid) return
        accessToken = nextToken
        accountGuid = nextGuid
        _state.value = CloudFavoriteState()
    }

    fun loadBooks() {
        if (_state.value.loading || accountGuid.isBlank()) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                ensureGuid()
                val mine = client().fetchMyRecipes()
                _state.update {
                    it.copy(
                        books = mine.books,
                        recipeCount = mine.recipeCount,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describe(e, "IF_FR100_H05"), detail = detailOf(e)) }
            }
        }
    }

    /**
     * 把方案复制进指定的书（`IF_IR_R_PR001`）。
     *
     * 同名方案重复收藏会各生成一份，服务端不查重——这是 Web 端行为，这里照抄，不擅自去重。
     */
    fun favoriteInto(recipeId: String, book: CloudBook) {
        if (recipeId.isBlank() || _state.value.working) return
        if (book.recipeCount >= CloudBook.MAX_RECIPES_PER_BOOK) {
            _state.update { it.copy(error = "《${book.label}》已满 ${CloudBook.MAX_RECIPES_PER_BOOK} 个方案，换一个收藏夹或新建。") }
            return
        }
        _state.update { it.copy(working = true, error = null, detail = null, phase = "正在收藏…") }
        viewModelScope.launch {
            try {
                ensureGuid()
                val created = client().favoriteRecipes(book.id, listOf(recipeId))
                _state.update {
                    it.copy(
                        working = false,
                        phase = null,
                        notice = if (created.isEmpty()) {
                            "已收藏到《${book.label}》"
                        } else {
                            "已收藏到《${book.label}》（共 ${created.size} 个）"
                        },
                    )
                }
                refreshBooksQuietly()
            } catch (e: Exception) {
                _state.update {
                    it.copy(working = false, phase = null, error = describe(e, "IF_IR_R_PR001"), detail = detailOf(e))
                }
            }
        }
    }

    /** 没有书、或想单独放时：新建一本再收藏（`IF_IR_R_PB002` → `IF_IR_R_PR001`）。 */
    fun createBookAndFavorite(recipeId: String, name: String = DEFAULT_BOOK_NAME) {
        if (recipeId.isBlank() || _state.value.working) return
        if (!_state.value.canCreateBook) {
            _state.update { it.copy(error = "一个账号最多 ${CloudBook.MAX_BOOKS} 个收藏夹，先在网页端删一个。") }
            return
        }
        _state.update { it.copy(working = true, error = null, detail = null, phase = "正在新建收藏夹…") }
        viewModelScope.launch {
            try {
                ensureGuid()
                val bookId = client().createBook(name)
                _state.update { it.copy(phase = "正在把方案放进新收藏夹…") }
                val created = client().favoriteRecipes(bookId, listOf(recipeId))
                _state.update {
                    it.copy(
                        working = false,
                        phase = null,
                        notice = if (created.isEmpty()) {
                            "已新建《$name》并收藏"
                        } else {
                            "已新建《$name》并收藏 ${created.size} 个方案"
                        },
                    )
                }
                refreshBooksQuietly()
            } catch (e: Exception) {
                _state.update {
                    it.copy(working = false, phase = null, error = describe(e, "IF_IR_R_PB002"), detail = detailOf(e))
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null, detail = null) }
    }

    /**
     * 收藏接口的路径里带账号 guid；登录时可能只抓到 token，所以这里先向 OIDC userinfo
     * 要一次权威 guid（`GET .../protocol/openid-connect/userinfo`），避免拼出空段 URL。
     */
    private suspend fun ensureGuid() {
        // 登录时抓到的 guid 可能是从 URL 里猜的（方案 ID / bookId 都是 26 位 ULID，形态一样），
        // 拿它拼 /publicbook/ 会得到 422，所以这里每次都问一次 OIDC userinfo 拿权威值。
        val identity = CloudAccountClient(accessToken = accessToken, guid = accountGuid).fetchIdentity()
        if (identity.guid.isNotBlank()) accountGuid = identity.guid
    }

    private fun detailOf(e: Exception): String? {
        val raw = e.message.orEmpty()
        return raw.takeIf { it.isNotBlank() }?.let { "详情：$it" }
    }

    fun consumeNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun refreshBooksQuietly() {
        viewModelScope.launch {
            runCatching { client().fetchMyRecipes() }.onSuccess { mine ->
                _state.update { it.copy(books = mine.books, recipeCount = mine.recipeCount) }
            }
        }
    }

    private fun client() = CloudAccountClient(accessToken = accessToken, guid = accountGuid)

    private fun describe(e: Throwable, callId: String): String = when {
        e is CloudException && e.errorCode.isNotBlank() -> "$callId 返回 ${e.errorCode}"
        e is CloudException && (e.httpStatus == 401 || e.httpStatus == 403) ->
            "$callId 认证失败（${e.httpStatus}）：重新登录一次云创"
        else -> "$callId：${e.message.orEmpty().ifBlank { e::class.simpleName.orEmpty() }}"
    }

    private companion object {
        const val DEFAULT_BOOK_NAME = "NiKConnect"
    }
}
