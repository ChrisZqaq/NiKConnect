package com.chrisz.nikconnect.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisz.nikconnect.ptp.DeviceInfo
import com.chrisz.nikconnect.ptp.PhotoObject

// 一次下载超过这个体量先让用户确认（60 张 NEF 约 1 GB）。
private const val BULK_CONFIRM_MB = 200L

@Composable
internal fun NiKConnectApp(model: MainViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    NiKConnectContent(
        state = state,
        onConnect = { model.connect("") },
        onRefresh = model::refreshPhotos,
        onDownload = model::download,
        onDownloadAll = model::downloadAll,
        onLoadMore = model::loadMorePhotos,
        onRetryDownload = model::retryDownload,
        onCancelDownload = model::cancelDownload,
        onDisconnect = model::disconnect,
        onTab = model::selectTab,
        onViewTier = model::setGalleryViewTier,
        onCloudLogin = model::onCloudLogin,
        onCloudLogout = model::cloudLogout,
        thumbnailLoader = model::thumbnail,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NiKConnectContent(
    state: UiState,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (PhotoObject) -> Unit,
    onDownloadAll: (List<PhotoObject>) -> Unit,
    onLoadMore: () -> Unit,
    onRetryDownload: (Long) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onDisconnect: () -> Unit,
    onTab: (Int) -> Unit,
    onViewTier: (Int) -> Unit,
    onCloudLogin: (CloudAuthState) -> Unit,
    onCloudLogout: () -> Unit,
    thumbnailLoader: suspend (Long) -> ByteArray,
) {
    val connected = state.connectionStatus == ConnectionStatus.CONNECTED
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NIKCONNECT", fontWeight = FontWeight.Black) },
                actions = {
                    if (connected) {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新照片")
                        }
                        TextButton(onClick = onDisconnect) { Text("断开") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                // 三个入口常驻：断线时「传输」仍要能看到已完成/失败的结果与原因，不该因为断开就消失。
                NavigationBarItem(
                    selected = state.selectedTab == 0,
                    onClick = { onTab(0) },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text("照片") },
                )
                NavigationBarItem(
                    selected = state.selectedTab == 1,
                    onClick = { onTab(1) },
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    label = { Text("传输") },
                )
                NavigationBarItem(
                    selected = state.selectedTab == 2,
                    onClick = { onTab(2) },
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                    label = { Text("云创") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.selectedTab == 2 -> CloudTab(
                    cloudToken = state.cloudToken,
                    cloudGuid = state.cloudGuid,
                    onLogin = onCloudLogin,
                    onLogout = onCloudLogout,
                    cloudRefreshToken = state.cloudRefreshToken,
                )
                // 传输页与连接状态解耦：断线后仍可查看结果、失败原因与重试入口
                state.selectedTab == 1 -> TransferScreen(state, onRetryDownload, onCancelDownload, onGoPhotos = { onTab(0) })
                connected -> GalleryScreen(state, onDownload, onDownloadAll, onLoadMore, onViewTier, thumbnailLoader)
                else -> ConnectionScreen(state, onConnect)
            }
        }
    }
}

@Composable
private fun ConnectionScreen(state: UiState, onConnect: () -> Unit) {
    val connecting = state.connectionStatus == ConnectionStatus.CONNECTING
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("连接相机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "PTP/IP 直连，照片不经过任何服务器。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ConnectionStep("1", "相机开启 Wi-Fi", "网络菜单 → 连接至智能设备 → Wi-Fi 连接")
                HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                ConnectionStep("2", "手机连接相机 Wi-Fi", "无互联网时选择“保持连接”")
            }
        }
        state.error?.let { message ->
            ConnectionErrorCard(
                message = message,
                canRetry = !connecting,
                onRetry = onConnect,
            )
        }
        Button(
            onClick = onConnect,
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (connecting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(12.dp))
            } else {
                Icon(Icons.Default.Wifi, contentDescription = null)
                Spacer(Modifier.size(8.dp))
            }
            Text(if (connecting) "连接中…" else "连接")
        }
    }
}

@Composable
private fun ConnectionStep(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val VIEW_TIERS = listOf("大", "中", "小")

@Composable
private fun GalleryScreen(
    state: UiState,
    onDownload: (PhotoObject) -> Unit,
    onDownloadAll: (List<PhotoObject>) -> Unit,
    onLoadMore: () -> Unit,
    onViewTier: (Int) -> Unit,
    thumbnailLoader: suspend (Long) -> ByteArray,
) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var viewerPhoto by remember { mutableStateOf<PhotoObject?>(null) }
    var filter by rememberSaveable { mutableStateOf("全部") }  // 全部 / JPEG / RAW
    val filteredPhotos = when (filter) {
        "JPEG" -> state.photos.filter { it.isJpeg }
        "RAW" -> state.photos.filter { it.isRaw }
        else -> state.photos
    }
    val viewTier = state.galleryViewTier
    val completedHandles = state.downloads
        .filter { it.status == DownloadStatus.COMPLETED }
        .map { it.photo.handle }
        .toSet() + state.photos
            .filter { it.filename in state.downloadedFilenames }
            .map { it.handle }
    val viewMinSize = listOf(168.dp, 96.dp, 64.dp)[viewTier]
    // 选择集与会话绑定，不随「当前页句柄列表」变化而清空（翻页加载更多不该丢选中）
    val selected = rememberSaveable(
        saver = listSaver(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(setOf<Long>()) }
    fun exitSelection() {
        selectionMode = false
        selected.value = emptySet()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "JPEG", "RAW").forEach { tier ->
                    FilterChip(
                        selected = filter == tier,
                        onClick = { filter = tier },
                        label = { Text(tier) },
                    )
                }
            }
            if (filteredPhotos.isNotEmpty()) {
                TextButton(onClick = {
                    onViewTier((viewTier + 1) % VIEW_TIERS.size)
                }) {
                    Icon(Icons.Default.GridView, contentDescription = "切换视图大小")
                    Spacer(Modifier.width(4.dp))
                    Text(VIEW_TIERS[viewTier])
                }
            }
        }
        if (state.error != null) ErrorCard(state.error, Modifier.padding(horizontal = 20.dp))
        when {
            state.loadingPhotos -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("读取中…")
                }
            }
            state.photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无照片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = viewMinSize),
                    contentPadding = PaddingValues(16.dp, if (selectionMode) 16.dp else 16.dp, 16.dp, if (selectionMode) 96.dp else 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredPhotos, key = { it.handle }) { photo ->
                        val onToggle: ((Long) -> Unit)? = if (selectionMode) {
                            { handle: Long ->
                                selected.value = if (handle in selected.value) {
                                    selected.value - handle
                                } else {
                                    selected.value + handle
                                }
                            }
                        } else {
                            null
                        }
                        PhotoCard(
                            photo = photo,
                            compact = viewTier == 2,
                            downloaded = photo.handle in completedHandles,
                            selectionMode = selectionMode,
                            isSelected = photo.handle in selected.value,
                            onToggleSelect = onToggle,
                            onDownload = onDownload,
                            onOpen = if (!selectionMode && (photo.isJpeg || photo.isRaw)) {
                                { viewerPhoto = photo }
                            } else null,
                            onLongPress = if (!selectionMode && state.photos.isNotEmpty()) {
                                { selectionMode = true; selected.value = setOf(photo.handle) }
                            } else null,
                            thumbnailLoader = thumbnailLoader,
                        )
                    }
                    if (state.hasMore) {
                        item(key = "load-more") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                } else {
                                    TextButton(onClick = onLoadMore) {
                                        Text("加载更多（${state.photos.size}/${state.totalPhotoCount}）")
                                    }
                                }
                            }
                        }
                    }
                }
        viewerPhoto?.let { photo ->
            PhotoViewer(
                photo = photo,
                downloaded = photo.handle in completedHandles ||
                    photo.filename in state.downloadedFilenames,
                thumbnailLoader = thumbnailLoader,
                onDownload = {
                    onDownload(photo)
                    viewerPhoto = null
                },
                onSelect = {
                    selectionMode = true
                    selected.value = setOf(photo.handle)
                    viewerPhoto = null
                },
                onDismiss = { viewerPhoto = null },
            )
        }
                if (selectionMode) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        tonalElevation = 3.dp,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 只对「当前筛选下可见的」照片全选，避免 JPEG 视图里把隐藏的 RAW 也选中
                            val downloadable = filteredPhotos.filter { it.isJpeg || it.isRaw }
                            val allSelected = downloadable.isNotEmpty() &&
                                downloadable.all { it.handle in selected.value }
                            TextButton(onClick = {
                                selected.value = if (allSelected) {
                                    emptySet()
                                } else {
                                    downloadable.map { it.handle }.toSet()
                                }
                            }) {
                                Text(if (allSelected) "取消全选" else "全选")
                            }
                            // 一次全选 60 张就是 0.9 GB 级别的流量（真机踩过），超过阈值先确认。
                            val chosen = filteredPhotos.filter { it.handle in selected.value }
                            var confirmBulk by remember { mutableStateOf(false) }
                            // 数量必须与实际要下的集合一致：切换筛选后 selected 里可能残留隐藏项
                            // （评审 F15，早期按钮显示 3 实际只下 1 张）。
                            Button(
                                onClick = { confirmBulk = true },
                                enabled = chosen.isNotEmpty(),
                            ) {
                                Text("下载所选 (${chosen.size})")
                            }
                            if (confirmBulk) {
                                val total = chosen.sumOf { it.size }
                                val megabytes = total / (1024 * 1024)
                                AlertDialog(
                                    onDismissRequest = { confirmBulk = false },
                                    title = { Text("下载 ${chosen.size} 个文件？") },
                                    text = {
                                        Text(
                                            if (megabytes >= BULK_CONFIRM_MB) {
                                                "共约 $megabytes MB，会全部保存到相册的 NiKConnect 文件夹。"
                                            } else {
                                                "共约 $megabytes MB。"
                                            },
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            confirmBulk = false
                                            onDownloadAll(chosen)
                                            exitSelection()
                                        }) { Text("开始下载") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { confirmBulk = false }) { Text("取消") }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCard(
    photo: PhotoObject,
    compact: Boolean,
    downloaded: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: ((Long) -> Unit)?,
    onDownload: (PhotoObject) -> Unit,
    onOpen: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    thumbnailLoader: suspend (Long) -> ByteArray,
) {
    val bytes by produceState<ByteArray?>(initialValue = null, photo.handle) {
        value = runCatching { thumbnailLoader(photo.handle) }.getOrNull()
    }
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.combinedClickable(
            onClick = {
                when {
                    selectionMode && onToggleSelect != null -> onToggleSelect(photo.handle)
                    !selectionMode && onOpen != null -> onOpen()
                }
            },
            onLongClick = { onLongPress?.invoke() },
        ),
    ) {
        Column {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(if (compact) 1f else 1.35f).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!,
                        contentDescription = photo.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(if (photo.isRaw) "NEF" else "JPEG", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (downloaded) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已下载",
                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (selectionMode) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "已选中" else "未选中",
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        },
                    )
                }
            }
            if (!compact) Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(photo.filename, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatBytes(photo.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!selectionMode) {
                    Button(
                        onClick = { onDownload(photo) },
                        enabled = photo.isJpeg || photo.isRaw,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (photo.isJpeg || photo.isRaw) "下载" else "暂不支持")
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.StatCard(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.weight(1f),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhotoViewer(
    photo: PhotoObject,
    downloaded: Boolean,
    thumbnailLoader: suspend (Long) -> ByteArray,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bytes by produceState<ByteArray?>(initialValue = null, photo.handle) {
        value = runCatching { thumbnailLoader(photo.handle) }.getOrNull()
    }
    val bitmap = remember(bytes) {
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(photo.filename, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "关闭") }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = photo.filename,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text("选择此照片")
                    }
                    Button(
                        onClick = onDownload,
                        enabled = !downloaded && (photo.isJpeg || photo.isRaw),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Text(if (downloaded) "已下载" else if (photo.isJpeg || photo.isRaw) "下载这张" else "暂不支持")
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferScreen(
    state: UiState,
    onRetry: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onGoPhotos: () -> Unit,
) {
    if (state.downloads.isNotEmpty()) {
        val done = state.downloads.count { it.status == DownloadStatus.COMPLETED }
        val failed = state.downloads.count { it.status == DownloadStatus.FAILED }
        val active = state.downloads.count { it.status == DownloadStatus.WAITING || it.status == DownloadStatus.DOWNLOADING }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatCard("传输中", active.toString())
            StatCard("已完成", done.toString())
            StatCard("失败", failed.toString())
        }
    }
    if (state.downloads.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("传输", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "这里会显示每张照片的下载进度、失败原因和重试入口。先到「照片」页选中要传的照片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onGoPhotos, modifier = Modifier.heightIn(min = 48.dp)) { Text("去选择照片") }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(state.downloads, key = { it.photo.handle }) { item ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.photo.filename, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (item.status) {
                                DownloadStatus.WAITING -> "排队中"
                                DownloadStatus.DOWNLOADING -> "下载中"
                                DownloadStatus.COMPLETED -> "已完成"
                                DownloadStatus.FAILED -> "失败"
                                DownloadStatus.CANCELLED -> "已取消"
                            },
                            color = if (item.status == DownloadStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.COMPLETED) {
                        LinearProgressIndicator(
                            progress = {
                                if (item.photo.size == 0L) 0f else item.transferred.toFloat() / item.photo.size
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${formatBytes(item.transferred)} / ${formatBytes(item.photo.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (item.status) {
                            DownloadStatus.COMPLETED -> Text(
                                "已保存到相册",
                                color = MaterialTheme.colorScheme.primary,
                            )
                            DownloadStatus.FAILED -> {
                                Text(
                                    item.error ?: "传输中断",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(onClick = { onRetry(item.photo.handle) }) {
                                    Text("重试")
                                }
                            }
                            DownloadStatus.WAITING, DownloadStatus.DOWNLOADING -> TextButton(
                                onClick = { onCancel(item.photo.handle) },
                            ) {
                                Text("取消")
                            }
                            DownloadStatus.CANCELLED -> Button(onClick = { onRetry(item.photo.handle) }) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * 连接失败：先说人话和下一步，技术异常收进「诊断详情」。
 * 之前直接把 ECONNREFUSED / IP / 端口铺在红色卡里，用户无法判断该查相机还是查手机网络。
 */
@Composable
private fun ConnectionErrorCard(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var showDiagnostics by remember { mutableStateOf(false) }
    val looksLikeNetwork = message.contains("refused", ignoreCase = true) ||
        message.contains("timeout", ignoreCase = true) ||
        message.contains("timed out", ignoreCase = true) ||
        message.contains("unreachable", ignoreCase = true) ||
        message.contains("failed to connect", ignoreCase = true)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (looksLikeNetwork) "没找到相机" else "连接没有成功",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "确认手机当前连的是相机的 Wi-Fi（不是家里的路由器），相机处于「连接至智能设备 → Wi-Fi 连接」状态，然后再试一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Wi-Fi 设置", maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
                Button(
                    onClick = onRetry,
                    // 连不上时 lastHost 可能为空（首次是自动发现），重试要能重新发现，不能因为没记住 IP 就禁用
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("重试", maxLines = 1) }
            }
            TextButton(
                onClick = { showDiagnostics = !showDiagnostics },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (showDiagnostics) "收起诊断详情" else "诊断详情")
                Icon(
                    if (showDiagnostics) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (showDiagnostics) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun GalleryPreview() {
    val photo = PhotoObject(7, 1, 0x3801, 8_500_000, "DSC_0007.JPG", "20261027T143900", 20_000)
    NiKConnectTheme {
        NiKConnectContent(
            state = UiState(
                connectionStatus = ConnectionStatus.CONNECTED,
                deviceInfo = DeviceInfo("Nikon", "Z f", "1.30", emptyList()),
                photos = listOf(photo),
                totalPhotoCount = 1,
            ),
            onConnect = {},
            onRefresh = {},
            onDownload = {},
            onDownloadAll = {},
            onLoadMore = {},
            onRetryDownload = {},
            onCancelDownload = {},
            onDisconnect = {},
            onTab = {},
            onViewTier = {},
            onCloudLogin = {},
            onCloudLogout = {},
            thumbnailLoader = { ByteArray(0) },
        )
    }
}
