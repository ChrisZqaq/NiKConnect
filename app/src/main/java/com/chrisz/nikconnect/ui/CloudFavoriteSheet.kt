package com.chrisz.nikconnect.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.chrisz.nikconnect.cloud.CloudBook

/**
 * 详情页的「收藏」入口。
 *
 * 为什么需要它：官方目录里的方案只是"看得见"，账号里没有它就不能分配给相机。
 * `IF_IR_R_LR002` 会把它复制一份进「我的方案库」，之后才会出现在
 * `IF_FR100_H13` 的可分配池里（也就是账号页「＋」能挑到的列表）。
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
    var sheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(token, guid) { model.updateCredentials(token, guid) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            model.consumeNotice()
            sheetOpen = false
        }
    }

    Button(
        onClick = {
            sheetOpen = true
            model.loadBooks()
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.Filled.BookmarkAdded, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  收藏", style = MaterialTheme.typography.labelLarge)
    }

    if (sheetOpen) {
        FavoriteSheet(
            recipeId = recipeId,
            recipeName = recipeName,
            state = state,
            onPick = { book -> model.favoriteInto(recipeId, book) },
            onCreate = { model.createBookAndFavorite(recipeId) },
            onSaveToLibrary = { model.saveToLibrary(recipeId, recipeName) },
            onRetry = model::loadBooks,
            onDismiss = {
                model.clearError()
                sheetOpen = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteSheet(
    recipeId: String,
    recipeName: String,
    state: CloudFavoriteState,
    onPick: (CloudBook) -> Unit,
    onCreate: () -> Unit,
    onSaveToLibrary: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("收藏", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "$recipeName · 复制一份到你的收藏，之后就能排进相机槽位",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            state.phase?.let { phase ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        phase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.detail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                )
            }

            when {
                state.loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }

                state.error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
                }

                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(enabled = !state.working, onClick = onSaveToLibrary)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.BookmarkAdded,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text("收藏到我的收藏", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "之后可在相机槽位「＋」里选它",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    if (state.books.isEmpty()) {
                        Text(
                            "还没有收藏夹。新建一个即可收藏。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    state.books.forEach { book ->
                        BookRow(book = book, enabled = !state.working, onClick = { onPick(book) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                    if (state.canCreateBook) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(enabled = !state.working, onClick = onCreate)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text("新建收藏夹并收藏", style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        Text(
                            "一个账号最多 ${CloudBook.MAX_BOOKS} 个收藏夹，先在网页端删一个。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }

            if (state.working) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
            }
        }
    }
}

@Composable
private fun BookRow(book: CloudBook, enabled: Boolean = true, onClick: () -> Unit) {
    val full = book.recipeCount >= CloudBook.MAX_RECIPES_PER_BOOK
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled && !full, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    book.label.ifBlank { "未命名收藏夹" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    if (full) "已满 ${book.recipeCount} 个方案" else "${book.recipeCount} 个方案",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (full) "已满" else "收藏",
                style = MaterialTheme.typography.labelLarge,
                color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
