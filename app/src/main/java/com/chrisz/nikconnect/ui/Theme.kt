package com.chrisz.nikconnect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 设计令牌取自 nikon-companion-prototype.html（深色摄影器材风）
private val AccentGold = Color(0xFFC3A36E)
private val ConnectedGreen = Color(0xFF79A88D)
private val Scheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = Color(0xFF12100E),
    secondary = ConnectedGreen,
    background = Color(0xFF12100E),
    surface = Color(0xFF1A1714),
    surfaceVariant = Color(0xFF221E1A),
    // 底部导航选中态默认取 secondaryContainer，不指定会落到 Material 基线的紫色
    secondaryContainer = Color(0xFF2E2822),
    onSecondaryContainer = AccentGold,
    outline = Color(0xFF35312C),
    onBackground = Color(0xFFE9E5DC),
    onSurface = Color(0xFFE9E5DC),
    onSurfaceVariant = Color(0xFF969087),
    error = Color(0xFFE29A8A),
)

@Composable
internal fun NiKConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
