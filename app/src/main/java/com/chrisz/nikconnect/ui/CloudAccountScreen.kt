package com.chrisz.nikconnect.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisz.nikconnect.cloud.CloudRecipe
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

        state.guid?.takeIf { it.isNotBlank() }?.let { GuidLine(it) }
        if (state.needsGuidRefresh) {
            GuidRefreshBar(onRecaptureGuid)
        }
        if (!state.hasCredentials) {
            Notice("没有拿到账号 ID。点右上角刷新重试；仍然失败就退出后重新登录云创。")
        } else if (state.selected == null) {
            CameraSection(state = state, onOpen = model::openCamera, onLogout = onLogout)
        } else {
            SlotSection(
                state = state,
                onRetry = { model.openCamera(state.selected!!) },
                onEdit = model::beginEdit,
                onCancelEdit = model::cancelEdit,
                onMove = model::moveSlot,
                onClear = model::clearSlot,
                onPick = model::openSlotPicker,
                onSave = model::saveLayout,
                onNoticeShown = model::consumeNotice,
            )
        }
        state.pickingSlot?.let { position ->
            SlotPicker(
                position = position,
                state = state,
                onPick = { recipe -> model.fillSlot(position, recipe) },
                onRetry = { model.loadAssignable() },
                onDismiss = model::dismissSlotPicker,
            )
        }
    }
}

/** 账号 ID 明文一行：出问题时用户能直接读出来反馈，不用去装抓包工具。 */
@Composable
private fun GuidLine(guid: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "账号 ID",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        SelectionContainer {
            Text(
                guid,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            "服务端不认这个账号 ID",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRecaptureGuid, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("退出重登")
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
                            val sub = buildList {
                                if (camera.model.isNotBlank()) add(camera.model)
                                if (camera.serial.isNotBlank()) add("SN ${camera.serial}")
                                add(camera.id.take(8))
                            }.joinToString(" · ")
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
            "说明：云创方案占相机内的 9 个云创槽位（独立存储区），不是机身菜单的 C-1…C-9。方案由相机自己联网拉取；点进相机可编辑槽位排布。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onLogout, modifier = Modifier.heightIn(min = 48.dp)) { Text("退出云创账号") }
    }
}

@Composable
private fun SlotSection(
    state: CloudAccountState,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: (Int) -> Unit,
    onPick: (Int) -> Unit,
    onSave: () -> Unit,
    onNoticeShown: () -> Unit,
) {
    val context = LocalContext.current
    val editing = state.draft != null
    val rows = state.draft ?: state.slots
    val rowHeight = 64.dp
    val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }

    LaunchedEffect(state.savedNotice) {
        state.savedNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onNoticeShown()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "云创槽位",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                TextButton(onClick = onCancelEdit, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("撤销")
                }
                Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("保存到相机")
                    }
                }
            } else {
                TextButton(
                    onClick = onEdit,
                    enabled = state.slots.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("编辑排布") }
            }
        }

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

        state.saveError?.let { Notice(it) }
        state.rawSnippet?.let { RawSnippet(it) }

        if (editing) {
            Text(
                "长按拖动换位 · 点击已占用槽位＝清空 · 点击空槽位＝从可分配池挑方案 · 保存后相机联网时会重新拉取",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        rows.forEachIndexed { position, slot ->
            var dragY by remember(position, editing) { mutableFloatStateOf(0f) }
            val dragging by remember(position, editing) { derivedStateOf { dragY != 0f } }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .graphicsLayer { translationY = dragY }
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (dragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                    )
                    .then(
                        if (editing) {
                            Modifier
                                .pointerInput(position, rowHeightPx, rows.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragEnd = {
                                            val shift = (dragY / rowHeightPx).roundToInt()
                                            dragY = 0f
                                            if (shift != 0) {
                                                onMove(position, (position + shift).coerceIn(0, rows.lastIndex))
                                            }
                                        },
                                        onDragCancel = { dragY = 0f },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragY += amount.y
                                        },
                                    )
                                }
                                .clickable {
                                    // 有方案 → 清空；空位 → 从可分配池里挑一个
                                    if (slot.occupied) onClear(position) else onPick(position)
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (position + 1).toString(),
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
                if (editing) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = "长按拖动",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (slot.modifiedAt.isNotBlank()) {
                    Text(
                        slot.modifiedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            "说明：云创方案占相机内的 9 个云创槽位（独立存储区），不是机身菜单的 C-1…C-9。编辑态下长按拖动换位、点击清空；有方案时保存＝整体覆盖排布，全部清空后保存＝逐个删除方案（`DELETE`）。提交到账号后，相机联网时会自己拉取；App 不代理方案文件。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/**
 * 空槽位选方案：只从「账号可分配池」（`IF_FR100_H13`）里挑。
 *
 * 为什么不能从 144 目录直接选：方案必须先收藏进账号的书，才会出现在可分配池；
 * 收藏是 `POST /publicrecipe/{guid}`，属于下一批。这里把边界显式告诉用户，
 * 而不是给一个"点了没反应"的列表。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SlotPicker(
    position: Int,
    state: CloudAccountState,
    onPick: (CloudRecipe) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "为第 ${position + 1} 个槽位选择方案",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "来源：账号可分配池（${state.assignable.size} 个）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.loadingAssignable) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            } else if (state.assignable.isEmpty()) {
                Text(
                    state.assignableError
                        ?: "可分配池是空的。先在云创网页把方案收藏到你的书，再回来这里挑选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.rawSnippet?.let { RawSnippet(it) }
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新读取") }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(state.assignable, key = { it.id }) { recipe ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(recipe) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(recipe.name, style = MaterialTheme.typography.bodyMedium)
                            if (recipe.pictureControlName.isNotBlank()) {
                                Text(
                                    recipe.pictureControlName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
