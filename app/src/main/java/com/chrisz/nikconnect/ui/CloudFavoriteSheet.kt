package com.chrisz.nikconnect.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

/**
 * 详情页的收藏按钮（单按钮 toggle）。
 *
 * 官方目录里的方案只是"看得见"，账号里没有它就不能分配给相机。
 * `IF_IR_R_LR002` 把它复制一份进「我的收藏」，之后才会出现在
 * `IF_FR100_H13` 的可分配池里（也就是账号页「＋」能挑到的列表）。
 * 已在收藏里时，点击弹二次确认后走 `IF_IR_R_LR003` 把它删掉。
 */
@Composable
internal fun CloudFavoriteBar(
    recipeId: String,
    recipeName: String,
    token: String?,
    guid: String?,
    onNeedLogin: () -> Unit,
    createdBy: String = "Nikon",
    refreshToken: String? = null,
    onTokenRefreshed: (String, String?) -> Unit = { _, _ -> },
) {
    if (token.isNullOrBlank()) {
        OutlinedButton(
            onClick = onNeedLogin,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.BookmarkAdded, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  登录云创后可收藏", style = MaterialTheme.typography.labelLarge)
        }
        return
    }

    val factory = viewModelFactory {
        initializer {
            CloudFavoriteViewModel(
                token = token.orEmpty(),
                guid = guid,
                refreshToken = refreshToken,
                onTokenRefreshed = onTokenRefreshed,
            )
        }
    }
    val model: CloudFavoriteViewModel = viewModel(key = "cloud-favorite-$guid", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf(false) }

    LaunchedEffect(token, guid) { model.updateCredentials(token, guid) }
    LaunchedEffect(recipeName) { model.loadCollection(recipeName) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            model.consumeNotice()
        }
    }

    val collected = state.collected
    Button(
        onClick = {
            if (collected != null) confirmRemove = true else model.toggleCollection(recipeId, recipeName)
        },
        enabled = !state.working,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = if (collected != null) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        when {
            state.working -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            collected != null -> {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  已收藏 · 点击取消", style = MaterialTheme.typography.labelLarge)
            }
            else -> {
                Icon(Icons.Filled.BookmarkAdded, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  收藏", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    state.error?.let { error ->
        Text(
            error,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    CloudAddToCameraBar(
        recipeId = recipeId,
        recipeName = recipeName,
        token = token,
        guid = guid,
        collectedLocalId = collected?.id,
        onNeedLogin = onNeedLogin,
        refreshToken = refreshToken,
        onTokenRefreshed = onTokenRefreshed,
    )

    if (confirmRemove && collected != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("取消收藏？") },
            text = { Text("会把「${collected.name}」从你的收藏里删掉。相机槽位里已排好的位置不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        model.removeFromCollection(collected.id)
                    },
                ) { Text("取消收藏", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("保留") }
            },
        )
    }
}
