package com.chrisz.nikconnect.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chrisz.nikconnect.cloud.CloudCamera

/**
 * 详情页第二个主操作：把这条方案直接排进某台相机的某个云创槽位。
 *
 * 之前用户必须先"收藏 → 回账号页 → 进相机 → 编辑排布 → 点＋"，四层跳转才知道方案去哪了；
 * 这里把整条链路（LR002 → C003 → C004）收在一次点击里，选位置时就把当前排布显示出来，
 * 避免把别的槽位覆盖掉。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CloudAddToCameraBar(
    recipeId: String,
    recipeName: String,
    token: String?,
    guid: String?,
    collectedLocalId: String?,
    onNeedLogin: () -> Unit,
    refreshToken: String? = null,
    onTokenRefreshed: (String, String?) -> Unit = { _, _ -> },
) {
    var open by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { if (token.isNullOrBlank()) onNeedLogin() else open = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.Filled.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            if (token.isNullOrBlank()) "  登录后可加到相机" else "  加到相机",
            style = MaterialTheme.typography.labelLarge,
        )
    }

    if (!open) return

    val factory = viewModelFactory {
        initializer {
            CloudAddToCameraViewModel(
                token = token.orEmpty(),
                refreshToken = refreshToken,
                capturedGuid = guid,
                onTokenRefreshed = onTokenRefreshed,
            )
        }
    }
    val model: CloudAddToCameraViewModel = viewModel(key = "cloud-add-$guid", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            model.consumeNotice()
            open = false
        }
    }

    ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("加到相机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "$recipeName · 选一个槽位，之后相机联网时会自己拉取",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.working?.let { step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(step, style = MaterialTheme.typography.labelMedium)
                }
            }

            state.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("读取相机…", style = MaterialTheme.typography.labelMedium)
                }

                state.cameras.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "账号里还没有注册的相机。先在相机上完成一次云创连接，机身会自己注册。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { model.load() }) { Text("重新读取") }
                }

                else -> state.cameras.forEach { camera ->
                    CameraSlotsBlock(
                        camera = camera,
                        slots = state.slots[camera.id].orEmpty(),
                        busy = state.working != null,
                        onPick = { position ->
                            model.addToSlot(
                                camera = camera,
                                position = position,
                                recipeId = recipeId,
                                recipeName = recipeName,
                                alreadyCollectedId = collectedLocalId,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraSlotsBlock(
    camera: CloudCamera,
    slots: List<com.chrisz.nikconnect.cloud.CloudSlot>,
    busy: Boolean,
    onPick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            camera.name.ifBlank { camera.model.ifBlank { camera.id } },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        slots.take(9).forEachIndexed { position, slot ->
            val label = if (slot.occupied) slot.name.ifBlank { slot.pictureControlName } else "空"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = !busy) { onPick(position) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    (position + 1).toString(),
                    modifier = Modifier.size(24.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (slot.occupied) {
                        MaterialTheme.colorScheme.onBackground
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (slot.occupied) "替换" else "放到这里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
