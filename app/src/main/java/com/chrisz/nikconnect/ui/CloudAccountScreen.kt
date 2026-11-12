package com.chrisz.nikconnect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * 云创账号页（0.6 批次 1：只读核对）。
 *
 * 目的：先把「账号 guid → 已注册相机 → 9 个云创槽位」这条链路在真机上打通，
 * 确认接口响应形状之后再做拖拽排布（批次 2）。
 */
@Composable
internal fun CloudAccountScreen(
    token: String,
    guid: String?,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onRecaptureGuid: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val factory = viewModelFactory {
        initializer { CloudAccountViewModel(token = token, guid = guid) }
    }
    val model: CloudAccountViewModel = viewModel(key = "cloud-account-$guid", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()

    // 同一个 guid 重新登录后 token 会变：把新凭据交给已有 ViewModel，旧请求随代次变化失效。
    LaunchedEffect(token, guid) { model.updateCredentials(token, guid) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (state.selected != null) model.backToList() else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                state.selected?.name ?: "云创账号",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = model::refreshCameras, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        if (state.needsGuidRefresh) {
            GuidRefreshBar(onRecaptureGuid)
        }
        if (!state.hasCredentials) {
            Notice("没有拿到账号 guid。点上面「重新获取账号 ID」，在网页里打开相机页即可自动捕获。")
        } else if (state.selected == null) {
            CameraSection(state = state, onOpen = model::openCamera, onLogout = onLogout)
        } else {
            SlotSection(state = state, onRetry = { model.openCamera(state.selected!!) })
        }
    }
}

@Composable
private fun GuidRefreshBar(onRecaptureGuid: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "账号 ID 可能不对（把方案 ID 当成了账号 ID）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRecaptureGuid, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("重新获取账号 ID")
        }
    }
}

@Composable
private fun CameraSection(
    state: CloudAccountState,
    onOpen: (com.chrisz.nikconnect.cloud.CloudCamera) -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "已注册相机",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        when {
            state.loadingCameras -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }

            else -> {
                state.cameraError?.let { Notice(it) }
                state.cameras.forEach { camera ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onOpen(camera) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                camera.name.ifBlank { camera.model.ifBlank { camera.id } },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val sub = listOf(camera.model, camera.id).filter { it.isNotBlank() }.joinToString(" · ")
                            if (sub.isNotBlank()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "槽位",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                state.assignableCount?.let {
                    Text(
                        "账号可分配方案：$it 个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.rawSnippet?.let { RawSnippet(it) }

        Text(
            "说明：云创方案占相机内的 9 个云创槽位（独立存储区），不是机身菜单的 C-1…C-9。方案由相机自己联网拉取；本页当前只做只读查看，排布写入在后续批次实现。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onLogout, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出云创账号") }
        TextButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("拖拽排布与收藏：后续批次")
        }
    }
}

@Composable
private fun SlotSection(state: CloudAccountState, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "云创槽位",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        if (state.loadingSlots) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            return@Column
        }

        state.slotsError?.let { error ->
            Notice(error)
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
        }

        state.rawSnippet?.let { RawSnippet(it) }

        state.slots.forEachIndexed { i, slot ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    slot.index.toString(),
                    modifier = Modifier.width(28.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        if (slot.occupied) slot.name.ifBlank { slot.pictureControlName } else "空",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (slot.occupied) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (slot.occupied) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (slot.occupied && slot.pictureControlName.isNotBlank() && slot.pictureControlName != slot.name) {
                        Text(
                            slot.pictureControlName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (slot.modifiedAt.isNotBlank()) {
                    Text(
                        slot.modifiedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (i != state.slots.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            }
        }
    }
}

/** 解析失败时把原始响应摊开，便于用户截图反馈（不含 token）。 */
@Composable
private fun RawSnippet(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "接口原始响应（可截图反馈）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
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

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
