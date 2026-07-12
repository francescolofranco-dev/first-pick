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


// Each tier gets its own hue so grades are told apart at a glance on a small seal. The old ramp
// put A+ (gold) and C (yellow-gold) within a few RGB points of each other; now the bomb gold is
// unique and the mid-tiers walk a saturated blue -> teal -> green cool ramp.
fun valueTierColor(value: Double?): Color = when {
    value == null -> Color(0xFF8A9691)
    value >= 80 -> Color(0xFFFFC02E) // Bomb — bright gold, used nowhere else on the ramp
    value >= 72 -> Color(0xFF57D670) // Great — green
    value >= 64 -> Color(0xFF2CC7A6) // Strong — teal
    value >= 56 -> Color(0xFF57A6F0) // Good — blue
    value >= 48 -> Color(0xFF93A2AE) // Playable — neutral slate
    value >= 40 -> Color(0xFFEC8A3C) // Filler — orange
    else -> Color(0xFFE45247)        // Weak — red
}

/** The bomb tier (A+) gets the standout filled seal treatment in the overlay. */
fun isBombTier(value: Double?): Boolean = value != null && value >= 80.0

/** Dark ink for text drawn on top of a filled tier color (the bomb seal). */
val TierInk = Color(0xFF0C100F)

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
