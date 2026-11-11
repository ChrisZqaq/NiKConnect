package com.chrisz.nikconnect.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisz.nikconnect.ptp.DeviceInfo
import com.chrisz.nikconnect.ptp.PhotoObject

@Composable
internal fun NiKConnectApp(model: MainViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    NiKConnectContent(
        state = state,
        onConnect = model::connect,
        onRefresh = model::refreshPhotos,
        onDownload = model::download,
        onDownloadAll = model::downloadAll,
        onLoadMore = model::loadMorePhotos,
        onRetryDownload = model::retryDownload,
        onCancelDownload = model::cancelDownload,
        onDisconnect = model::disconnect,
        onTab = model::selectTab,
        thumbnailLoader = model::thumbnail,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NiKConnectContent(
    state: UiState,
    onConnect: (String) -> Unit,
    onRefresh: () -> Unit,
    onDownload: (PhotoObject) -> Unit,
    onDownloadAll: (List<PhotoObject>) -> Unit,
    onLoadMore: () -> Unit,
    onRetryDownload: (Long) -> Unit,
    onCancelDownload: (Long) -> Unit,
    onDisconnect: () -> Unit,
    onTab: (Int) -> Unit,
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
            if (connected) {
                NavigationBar {
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
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!connected) {
                ConnectionScreen(state, onConnect)
            } else if (state.selectedTab == 0) {
                GalleryScreen(state, onDownload, onDownloadAll, onLoadMore, thumbnailLoader)
            } else {
                TransferScreen(state, onRetryDownload, onCancelDownload)
            }
        }
    }
}

@Composable
private fun ConnectionScreen(state: UiState, onConnect: (String) -> Unit) {
    var manualHost by rememberSaveable { mutableStateOf("") }
    val connecting = state.connectionStatus == ConnectionStatus.CONNECTING
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "连接相机",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ConnectionStep("1", "相机开启 Wi-Fi", "网络菜单 → 连接至智能设备 → Wi-Fi 连接")
                HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                ConnectionStep("2", "手机连接相机 Wi-Fi", "无互联网时选择“保持连接”")
            }
        }
        if (state.error != null) ErrorCard(state.error)
        OutlinedTextField(
            value = manualHost,
            onValueChange = { manualHost = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !connecting,
            singleLine = true,
            label = { Text("相机 IP（可选）") },
        )
        Button(
            onClick = { onConnect(manualHost) },
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
    thumbnailLoader: suspend (Long) -> ByteArray,
) {
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var viewTier by rememberSaveable { mutableIntStateOf(0) }  // 0=大 168dp 1=中 96dp 2=小 64dp
    val viewMinSize = listOf(168.dp, 96.dp, 64.dp)[viewTier]
    val selected = rememberSaveable(state.photos.map { it.handle }) {
        mutableStateOf(setOf<Long>())
    }
    fun exitSelection() {
        selectionMode = false
        selected.value = emptySet()
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selectionMode) "已选 ${selected.value.size} 项" else "照片",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (state.photos.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        viewTier = (viewTier + 1) % VIEW_TIERS.size
                    }) {
                        Icon(Icons.Default.GridView, contentDescription = "切换视图大小")
                        Spacer(Modifier.width(4.dp))
                        Text(VIEW_TIERS[viewTier])
                    }
                    TextButton(onClick = {
                        if (selectionMode) exitSelection() else selectionMode = true
                    }) {
                        Text(if (selectionMode) "取消" else "多选")
                    }
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
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.photos, key = { it.handle }) { photo ->
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
                            selectionMode = selectionMode,
                            isSelected = photo.handle in selected.value,
                            onToggleSelect = onToggle,
                            onDownload = onDownload,
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
                            val downloadable = state.photos.filter { it.isJpeg || it.isRaw }
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
                            Button(
                                onClick = {
                                    onDownloadAll(state.photos.filter { it.handle in selected.value })
                                    exitSelection()
                                },
                                enabled = selected.value.isNotEmpty(),
                            ) {
                                Text("下载所选 (${selected.value.size})")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoCard(
    photo: PhotoObject,
    compact: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: ((Long) -> Unit)?,
    onDownload: (PhotoObject) -> Unit,
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
        onClick = {
            when {
                selectionMode && onToggleSelect != null -> onToggleSelect(photo.handle)
                !selectionMode && (photo.isJpeg || photo.isRaw) -> onDownload(photo)
            }
        },
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
private fun TransferScreen(
    state: UiState,
    onRetry: (Long) -> Unit,
    onCancel: (Long) -> Unit,
) {
    if (state.downloads.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("暂无传输", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            thumbnailLoader = { ByteArray(0) },
        )
    }
}
