package com.chrisz.nikconnect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NikonYellow = Color(0xFFFFCD00)
private val Scheme = darkColorScheme(
    primary = NikonYellow,
    onPrimary = Color(0xFF171814),
    secondary = Color(0xFFD7D5C9),
    background = Color(0xFF111210),
    surface = Color(0xFF171814),
    surfaceVariant = Color(0xFF23241F),
    onBackground = Color(0xFFF3F1E8),
    onSurface = Color(0xFFF3F1E8),
    onSurfaceVariant = Color(0xFFAEAFA5),
    error = Color(0xFFFFB4AB),
)

@Composable
internal fun NiKConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
