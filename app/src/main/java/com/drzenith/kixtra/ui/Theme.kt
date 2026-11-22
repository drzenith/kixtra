package com.drzenith.kixtra.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Brand colors, taken from the app icon.
val BrandViolet = Color(0xFF7C3AED)
val BrandCyan = Color(0xFF06B6D4)

/** Readable-on-dark tints of the brand colors, for text and small accents. */
val AccentViolet = Color(0xFFA78BFA)
val AccentCyan = Color(0xFF22D3EE)

val LiveRed = Color(0xFFEF4444)

/** The icon's violet -> cyan gradient. */
val BrandGradient = Brush.horizontalGradient(listOf(BrandViolet, BrandCyan))

private val Ink = Color(0xFF0D0E1A)

private val colors = darkColorScheme(
    primary = AccentViolet,
    onPrimary = Color(0xFF1E1240),
    primaryContainer = Color(0xFF3B2A6B),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = AccentCyan,
    onSecondary = Color(0xFF032B33),
    secondaryContainer = Color(0xFF2E2560),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = AccentCyan,
    background = Ink,
    onBackground = Color(0xFFECECF4),
    surface = Ink,
    onSurface = Color(0xFFECECF4),
    surfaceVariant = Color(0xFF1C1D33),
    onSurfaceVariant = Color(0xFFA9AAC4),
    surfaceContainerLowest = Color(0xFF090A14),
    surfaceContainerLow = Color(0xFF12132A),
    surfaceContainer = Color(0xFF15162B),
    surfaceContainerHigh = Color(0xFF1E1F38),
    surfaceContainerHighest = Color(0xFF272844),
    outline = Color(0xFF3D3E5E),
    outlineVariant = Color(0xFF2A2B45),
)

@Composable
fun KixtraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
