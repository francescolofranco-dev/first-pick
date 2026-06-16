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

/** Quality tier color for a 0–100 pick VALUE — green = great, red = weak. */
fun valueTierColor(value: Double?): Color = when {
    value == null -> Color(0xFF9AA6A2)
    value >= 75 -> Color(0xFFFFCA63) // bomb (gold)
    value >= 63 -> Color(0xFF5FD08C) // great (green)
    value >= 54 -> Color(0xFF7FD1C4) // good (teal)
    value >= 46 -> Color(0xFFE3C766) // okay (yellow)
    value >= 38 -> Color(0xFFE0995F) // filler (orange)
    else -> Color(0xFFD9716B)        // weak (red)
}

/** One-word quality label for a pick VALUE. */
fun valueTierLabel(value: Double?): String = when {
    value == null -> "—"
    value >= 75 -> "Bomb"
    value >= 63 -> "Great"
    value >= 54 -> "Good"
    value >= 46 -> "Okay"
    value >= 38 -> "Filler"
    else -> "Weak"
}
