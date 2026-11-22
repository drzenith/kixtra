package com.drzenith.kixtra.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val KickGreen = Color(0xFF53FC18)
val LiveRed = Color(0xFFE5383B)

private val colors = darkColorScheme(
    primary = KickGreen,
    onPrimary = Color.Black,
    secondary = KickGreen,
    onSecondary = Color.Black,
    background = Color(0xFF0E0F10),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0E0F10),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1B1D1F),
    onSurfaceVariant = Color(0xFFB0B3B8),
    surfaceContainer = Color(0xFF17191B),
    surfaceContainerHigh = Color(0xFF202326),
    outline = Color(0xFF3A3D41),
)

@Composable
fun KixtraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
