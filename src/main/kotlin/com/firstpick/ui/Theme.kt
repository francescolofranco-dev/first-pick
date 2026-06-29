package com.firstpick.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val DraftColorScheme = darkColorScheme(
    primary = Color(0xFF7FD1C4),
    onPrimary = Color(0xFF06302A),
    primaryContainer = Color(0xFF12433C),
    onPrimaryContainer = Color(0xFFB4ECE2),
    secondary = Color(0xFFAEC6C0),
    tertiary = Color(0xFFFFCA63),
    background = Color(0xFF0E1312),
    surface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFF26302D),
    onSurface = Color(0xFFE6EAE7),
    onSurfaceVariant = Color(0xFF9FACA7),
    error = Color(0xFFFF8F84),
    outline = Color(0xFF3A4540),
)

fun pipColor(c: Char): Color = when (c) {
    'W' -> Color(0xFFEDE6C8)
    'U' -> Color(0xFF4A90D9)
    'B' -> Color(0xFF8A7CA8)
    'R' -> Color(0xFFD9534F)
    'G' -> Color(0xFF5FB36A)
    else -> Color(0xFF9AA6A2)
}


fun valueTierColor(value: Double?): Color = when {
    value == null -> Color(0xFF9AA6A2)
    value >= 80 -> Color(0xFFFFCA63)
    value >= 72 -> Color(0xFF5FD08C)
    value >= 64 -> Color(0xFF7FD1C4)
    value >= 56 -> Color(0xFF8FCBBF)
    value >= 48 -> Color(0xFFE3C766)
    value >= 40 -> Color(0xFFE0995F)
    else -> Color(0xFFD9716B)
}

fun letterGrade(value: Double?): String = when {
    value == null -> "—"
    value >= 80 -> "A+"
    value >= 72 -> "A"
    value >= 64 -> "B+"
    value >= 56 -> "B"
    value >= 48 -> "C"
    value >= 40 -> "D"
    else -> "F"
}

fun valueTierLabel(value: Double?): String = when {
    value == null -> "—"
    value >= 80 -> "Bomb"
    value >= 72 -> "Great"
    value >= 64 -> "Strong"
    value >= 56 -> "Good"
    value >= 48 -> "Playable"
    value >= 40 -> "Filler"
    else -> "Weak"
}
