package com.firstpick.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** A calm dark scheme with a confident accent, tuned for an at-a-glance overlay. */
val DraftColorScheme = darkColorScheme(
    primary = Color(0xFF7FD1C4),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF1F4E47),
    onPrimaryContainer = Color(0xFFB4ECE2),
    secondary = Color(0xFFB5C9C4),
    tertiary = Color(0xFFFFCA63), // bomb star / highlights
    background = Color(0xFF0F1413),
    surface = Color(0xFF141B19),
    surfaceVariant = Color(0xFF293330),
    onSurface = Color(0xFFE2E6E3),
    onSurfaceVariant = Color(0xFFA7B3AF),
    error = Color(0xFFFFB4AB),
)

/** Approximate WUBRG colors for pips and bars. */
fun pipColor(c: Char): Color = when (c) {
    'W' -> Color(0xFFEDE6C8)
    'U' -> Color(0xFF4A90D9)
    'B' -> Color(0xFF8A7CA8)
    'R' -> Color(0xFFD9534F)
    'G' -> Color(0xFF5FB36A)
    else -> Color(0xFF9AA6A2)
}
