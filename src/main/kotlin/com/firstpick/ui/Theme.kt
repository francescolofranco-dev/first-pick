package com.firstpick.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/** A calm, premium dark scheme with a confident teal accent, tuned for at-a-glance reading. */
val DraftColorScheme = darkColorScheme(
    primary = Color(0xFF7FD1C4),
    onPrimary = Color(0xFF06302A),
    primaryContainer = Color(0xFF12433C),
    onPrimaryContainer = Color(0xFFB4ECE2),
    secondary = Color(0xFFAEC6C0),
    tertiary = Color(0xFFFFCA63), // bomb star / highlights
    background = Color(0xFF0E1312),
    surface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFF26302D),
    onSurface = Color(0xFFE6EAE7),
    onSurfaceVariant = Color(0xFF9FACA7),
    error = Color(0xFFFF8F84),
    outline = Color(0xFF3A4540),
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

// ---- Pick-quality scale -------------------------------------------------
// A single set of bands drives the letter grade, its color, and the word label so
// the badge, grade, and tier always agree.

/** Quality tier color for a 0–100 pick VALUE — gold = bomb, red = weak. */
fun valueTierColor(value: Double?): Color = when {
    value == null -> Color(0xFF9AA6A2)
    value >= 80 -> Color(0xFFFFCA63) // A+ bomb (gold)
    value >= 72 -> Color(0xFF5FD08C) // A  great (green)
    value >= 64 -> Color(0xFF7FD1C4) // B+ strong (teal)
    value >= 56 -> Color(0xFF8FCBBF) // B  good (soft teal)
    value >= 48 -> Color(0xFFE3C766) // C  playable (yellow)
    value >= 40 -> Color(0xFFE0995F) // D  filler (orange)
    else -> Color(0xFFD9716B)        // F  weak (red)
}

/** Letter grade for a 0–100 pick VALUE — the headline at-a-glance signal. */
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

/** One-word quality label for a pick VALUE (used in tooltips / secondary text). */
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
