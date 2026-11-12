package com.chrisz.nikconnect.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.cloud.CloudCatalogClient
import com.chrisz.nikconnect.cloud.CloudRecipe
import com.chrisz.nikconnect.cloud.CloudRecipeDetail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 排序项。官方接口返回的目录是"按上架时间升序"（2024 年的老方案在最前），
 * 直接沿用会让人以为列表从头就是最老的那批，所以默认改成最新在前。
 */
internal enum class CloudSort(val label: String) {
    NEWEST("最新"),
    POPULAR("热门"),
    OLDEST("最早"),
}

internal data class CloudCatalogState(
    val recipes: List<CloudRecipe> = emptyList(),
    /** 首屏用的是离线种子（144 条，无封面），联网刷新后才有图。 */
    val seedOnly: Boolean = true,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val query: String = "",
    val sort: CloudSort = CloudSort.NEWEST,
    val onlyNew: Boolean = false,
    val detail: CloudRecipeDetail? = null,
    val detailRequestId: String? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
) {
    val visible: List<CloudRecipe>
        get() {
            val q = query.trim()
            val filtered = recipes.filter { recipe ->
                (!onlyNew || recipe.isNew) &&
                    (q.isEmpty() || recipe.name.contains(q, ignoreCase = true) ||
                        recipe.pictureControlName.contains(q, ignoreCase = true))
            }
            return when (sort) {
                CloudSort.NEWEST -> filtered.sortedByDescending { it.publicDate }
                CloudSort.POPULAR -> filtered.sortedByDescending { it.likes }
                CloudSort.OLDEST -> filtered.sortedBy { it.publicDate }
            }
        }
}

/**
 * 云创目录浏览（批次 1：只读，不需要登录）。
 *
 * 状态原则：种子先上屏 → 后台刷新补封面；详情单独 load，失败不污染列表。
 */
internal class CloudCatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val client = CloudCatalogClient()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow(CloudCatalogState())
    val state: StateFlow<CloudCatalogState> = _state.asStateFlow()

    private var catalogJob: kotlinx.coroutines.Job? = null
    private var detailJob: kotlinx.coroutines.Job? = null

    init {
        loadSeed()
        refresh()
    }

    private fun loadSeed() {
        viewModelScope.launch {
            val seeded = withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().assets
                        .open("cloud/catalog_seed.json").bufferedReader().use { it.readText() }
                    json.decodeFromString(ListSerializer(CloudRecipe.serializer()), text)
                }.getOrNull()
            } ?: return@launch
            // 联网结果若已先到，不覆盖。
            if (_state.value.seedOnly && _state.value.recipes.isEmpty()) {
                _state.update { it.copy(recipes = seeded, seedOnly = true) }
            }
        }
    }

    fun refresh() {
        catalogJob?.cancel()
        _state.update {
            it.copy(
                loading = it.recipes.isEmpty(),
                refreshing = it.recipes.isNotEmpty(),
                error = null,
            )
        }
        catalogJob = viewModelScope.launch {
            try {
                val fresh = client.fetchCatalog()
                if (fresh.isNotEmpty()) {
                    _state.update { it.copy(recipes = fresh, seedOnly = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "云创目录加载失败") }
            } finally {
                _state.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun setSort(value: CloudSort) = _state.update { it.copy(sort = value) }

    fun toggleOnlyNew() = _state.update { it.copy(onlyNew = !it.onlyNew) }

    fun openDetail(recipeId: String) {
        detailJob?.cancel()
        _state.update {
            it.copy(detail = null, detailRequestId = recipeId, detailLoading = true, detailError = null)
        }
        detailJob = viewModelScope.launch {
            try {
                val detail = client.fetchDetail(recipeId)
                _state.update { it.copy(detail = detail) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(detailError = e.message ?: "方案详情加载失败") }
            } finally {
                _state.update { it.copy(detailLoading = false) }
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        _state.update {
            it.copy(detail = null, detailRequestId = null, detailLoading = false, detailError = null)
        }
    }
}
