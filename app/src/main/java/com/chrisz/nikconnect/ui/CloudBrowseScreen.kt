package com.chrisz.nikconnect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrisz.nikconnect.cloud.CLOUD_CAMERA_URL
import com.chrisz.nikconnect.cloud.CLOUD_INDEX_URL
import com.chrisz.nikconnect.cloud.CloudParam
import com.chrisz.nikconnect.cloud.CloudRecipe

/**
 * 云创 tab：原生浏览 + 账号侧槽位查看。
 *
 * 目录不需要登录；登录后从工具栏的人像图标进入账号页，只读查看「已注册相机 → 9 个云创槽位」。
 *
 * 设计约定（用户反馈"叠层太多"后重做）：封面不做渐变蒙版、不叠文字与角标；
 * 信息一律排在封面下方，用 字号/颜色 分层，不靠卡片套卡片。
 */
@Composable
internal fun CloudTab(
    cloudToken: String?,
    cloudGuid: String?,
    onLogin: (CloudAuthState) -> Unit,
    onLogout: () -> Unit,
) {
    val model: CloudCatalogViewModel = viewModel()
    val catalog by model.state.collectAsStateWithLifecycle()
    // 登录页起始地址：普通流程从方案库进；需要重新抓账号 ID 时直接打开网页「相机」页（该页自己会发 C001）。
    var loginStartUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    // 普通 remember（非 rememberSaveable）：进出 tab 必从第一条开始，位置不会被跨组合恢复。
    val gridState = remember { LazyGridState() }

    if (showAccount) {
        CloudAccountScreen(
            token = cloudToken.orEmpty(),
            guid = cloudGuid,
            onBack = { showAccount = false },
            onLogout = {
                showAccount = false
                onLogout()
            },
            onRecaptureGuid = {
                showAccount = false
                loginStartUrl = CLOUD_CAMERA_URL
            },
        )
        return
    }

    loginStartUrl?.let { startUrl ->
        CloudLoginScreen(
            onDone = { auth ->
                loginStartUrl = null
                if (auth.token != null) onLogin(auth)
                showAccount = true
            },
            onDismiss = { loginStartUrl = null },
            startUrl = startUrl,
        )
        return
    }

    val detail = catalog.detail
    when {
        detail != null -> CloudRecipeDetailScreen(
            detail = detail,
            onBack = model::closeDetail,
            onPrev = { detail.previousId?.let(model::openDetail) },
            onNext = { detail.nextId?.let(model::openDetail) },
        )

        catalog.detailLoading -> DetailShell(onBack = model::closeDetail) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }

        catalog.detailError != null -> DetailShell(onBack = model::closeDetail) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text(catalog.detailError!!, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { catalog.detailRequestId?.let(model::openDetail) }) { Text("重试") }
            }
        }

        else -> CloudBrowseContent(
            state = catalog,
            gridState = gridState,
            loggedIn = cloudToken != null,
            onRefresh = model::refresh,
            onQuery = model::setQuery,
            onSort = model::setSort,
            onToggleNew = model::toggleOnlyNew,
            onOpen = model::openDetail,
            onLoginClick = {
                if (cloudToken == null) loginStartUrl = CLOUD_INDEX_URL else showAccount = true
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CloudBrowseContent(
    state: CloudCatalogState,
    gridState: LazyGridState,
    loggedIn: Boolean,
    onRefresh: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: (CloudSort) -> Unit,
    onToggleNew: () -> Unit,
    onOpen: (String) -> Unit,
    onLoginClick: () -> Unit,
) {
    val visible = state.visible

    // 用户反馈：进入云创页曾停在列表最后一行。这里在进入/回到列表时强制落回顶部。
    LaunchedEffect(Unit) { gridState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        // 单行工具栏：标题 + 数量 + 账号 + 刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("云创", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            Text(
                "${state.recipes.size} 个方案",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onLoginClick, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = if (loggedIn) "云创账号与相机槽位" else "登录尼康账号",
                    tint = if (loggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing, modifier = Modifier.size(48.dp)) {
                if (state.refreshing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新目录")
                }
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp)
                .heightIn(min = 48.dp),
            placeholder = { Text("搜索方案名 / 英文代号", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
        )

        // 排序用纯文字切换，不再用 chip 胶囊堆叠
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CloudSort.entries.forEach { sort ->
                SortLabel(
                    text = sort.label,
                    selected = state.sort == sort,
                    onClick = { onSort(sort) },
                )
                Spacer(Modifier.width(18.dp))
            }
            SortLabel(text = "NEW", selected = state.onlyNew, onClick = onToggleNew)
        }

        if (state.error != null) {
            Text(
                text = if (state.recipes.isEmpty()) "目录加载失败：${state.error}" else "在线刷新失败，当前为离线数据",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRefresh)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.loading && state.recipes.isEmpty() -> SkeletonGrid()
                visible.isEmpty() -> EmptyState(
                    if (state.recipes.isEmpty()) "暂无方案" else "没有匹配的方案",
                    if (state.recipes.isEmpty()) "检查网络后下拉刷新" else "换个关键词，或清空筛选条件",
                )
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(visible, key = { it.id }) { recipe ->
                        CloudRecipeCard(recipe = recipe, onClick = { onOpen(recipe.id) })
                    }
                }
            }
        }
    }
}

/** 排序项：选中=主色 + 加粗，未选中=次要灰；只有文字，没有背景形状。 */
@Composable
private fun SortLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    )
}

/** 卡片：封面（无蒙版、无文字、无角标）+ 下方两行文字。 */
@Composable
private fun CloudRecipeCard(recipe: CloudRecipe, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            RemoteImage(
                url = recipe.coverUrl,
                contentDescription = recipe.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = { CoverPlaceholder() },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recipe.isNew) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                recipe.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            Text(
                recipe.pictureControlName.ifBlank { "—" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (recipe.likes > 0) {
                Icon(
                    Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    recipe.likes.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CoverPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            ),
    )
}

@Composable
private fun SkeletonGrid() {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(6) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(12.dp)),
            ) { CoverPlaceholder() }
        }
    }
}

@Composable
private fun EmptyState(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailShell(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(title = "", onBack = onBack)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** 详情页顶栏：返回 + 标题（替代原来盖在头图上的圆形半透明按钮）。 */
@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CloudRecipeDetailScreen(
    detail: com.chrisz.nikconnect.cloud.CloudRecipeDetail,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(title = detail.name, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(1.5f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                RemoteImage(
                    url = detail.coverUrl,
                    contentDescription = detail.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = { CoverPlaceholder() },
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        detail.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                    val chips = buildList {
                        if (detail.author.isNotBlank()) add(detail.author)
                        if (detail.baseName.isNotBlank()) add(detail.baseName)
                        if (detail.settingName.isNotBlank() && detail.settingName != detail.name) add(detail.settingName)
                    }
                    if (chips.isNotEmpty()) {
                        Text(
                            chips.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (detail.description.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("色彩说明")
                        Text(
                            detail.description,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
                        )
                    }
                }

                if (detail.params.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("优化校准参数")
                        ParamTable(detail.params)
                    }
                }

                if (detail.exif.isNotEmpty() || detail.camera.isNotBlank() || detail.lens.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("样片拍摄参数")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (detail.camera.isNotBlank()) ExifRow("机身", detail.camera)
                            if (detail.lens.isNotBlank()) ExifRow("镜头", detail.lens)
                            detail.exif.forEach { ExifRow(it.label, it.value) }
                        }
                    }
                }

                if (detail.previousId != null || detail.nextId != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (detail.previousId != null) {
                            OutlinedButton(
                                onClick = onPrev,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(
                                    "← ${detail.previousName.orEmpty()}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        if (detail.nextId != null) {
                            OutlinedButton(
                                onClick = onNext,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text(
                                    "${detail.nextName.orEmpty()} →",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }

                Text(
                    "方案由尼康云创提供。要把方案装进相机，相机需连上能上网的 Wi-Fi 并在相机端执行云创同步。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 参数表：一张平铺表，行间用发丝分割线；不再每个参数一张卡片。 */
@Composable
private fun ParamTable(params: List<CloudParam>) {
    val rows = params.chunked(2)
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                row.forEach { param ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            param.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            param.value,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            if (index != rows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun ExifRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
        )
    }
}
