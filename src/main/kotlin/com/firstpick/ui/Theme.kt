package com.firstpick.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
object AppColors {
    val background = Color(0xFF0A0E0D)
    val surface = Color(0xFF111716)
    val surfaceElevated = Color(0xFF171F1D)
    val surfaceInteractive = Color(0xFF1C2624)
    val surfaceSelected = Color(0xFF112A24)
    val surfacePopover = Color(0xFF151D1B)

    val borderSubtle = Color(0x14FFFFFF)
    val borderDefault = Color(0x24FFFFFF)
    val borderStrong = Color(0x38FFFFFF)
    val borderAccent = Color(0x666EE7B7)

    val textPrimary = Color(0xFFF0F4F2)
    val textSecondary = Color(0xFF9AB0A8)
    val textMuted = Color(0xFF687B74)
    val textOnAccent = Color(0xFF05241E)

    val accent = Color(0xFF6EE7B7)
    val accentSecondary = Color(0xFF93C5FD)
    val warning = Color(0xFFFBBF24)
    val danger = Color(0xFFF87171)
}

@Immutable
object AppSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 12.dp
    val xl: Dp = 16.dp
    val xxl: Dp = 24.dp
}

@Immutable
object AppRadii {
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val md: Dp = 8.dp
    val lg: Dp = 10.dp
    val xl: Dp = 14.dp
    val pill: Dp = 999.dp
}

@Immutable
object AppTypography {
    val appTitle = TextStyle(
        fontSize = 16.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    )
    val paneTitle = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    )
    val cardTitle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val rowTitle = TextStyle(
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
    )
    val body = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
    )
    val caption = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
    )
    val micro = TextStyle(
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
    )
    val sectionHeader = TextStyle(
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
    val monoValue = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    val monoSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    )
    val monoMicro = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
}

object AppTheme {
    val colors: AppColors get() = AppColors
    val spacing: AppSpacing get() = AppSpacing
    val radii: AppRadii get() = AppRadii
    val typography: AppTypography get() = AppTypography
}

val DraftColorScheme = darkColorScheme(
    primary = AppColors.accent,
    onPrimary = AppColors.textOnAccent,
    primaryContainer = AppColors.surfaceSelected,
    onPrimaryContainer = Color(0xFFB8F5DC),
    secondary = AppColors.accentSecondary,
    onSecondary = Color(0xFF082038),
    secondaryContainer = AppColors.surfaceInteractive,
    onSecondaryContainer = AppColors.textPrimary,
    tertiary = AppColors.warning,
    onTertiary = Color(0xFF281B00),
    tertiaryContainer = Color(0xFF2E2410),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = AppColors.background,
    onBackground = AppColors.textPrimary,
    surface = AppColors.surface,
    onSurface = AppColors.textPrimary,
    surfaceVariant = AppColors.surfaceElevated,
    onSurfaceVariant = AppColors.textSecondary,
    surfaceContainerLowest = AppColors.background,
    surfaceContainerLow = AppColors.surface,
    surfaceContainer = AppColors.surfaceElevated,
    surfaceContainerHigh = AppColors.surfaceInteractive,
    surfaceContainerHighest = AppColors.surfacePopover,
    inverseSurface = AppColors.surfacePopover,
    inverseOnSurface = AppColors.textPrimary,
    error = AppColors.danger,
    onError = Color(0xFF2D0606),
    outline = AppColors.borderDefault,
    outlineVariant = AppColors.borderSubtle,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DraftColorScheme) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides LocalScrollbarStyle.current.copy(
                thickness = 6.dp,
                shape = RoundedCornerShape(AppTheme.radii.pill),
                unhoverColor = AppColors.borderDefault,
                hoverColor = AppColors.textSecondary,
            ),
            content = content,
        )
    }
}

fun Modifier.appPanel(
    padding: Dp = AppTheme.spacing.lg,
    radius: Dp = AppTheme.radii.lg,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(AppColors.surface)
        .border(1.dp, AppColors.borderSubtle, shape)
        .padding(padding)
}

fun Modifier.appCard(
    selected: Boolean = false,
    active: Boolean = false,
    radius: Dp = AppTheme.radii.md,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val bg = if (selected || active) AppColors.surfaceSelected else AppColors.surface
    val borderColor = when {
        active -> AppColors.accent
        selected -> AppColors.borderAccent
        else -> AppColors.borderSubtle
    }
    val borderWidth = if (active) 1.5.dp else 1.dp
    return this
        .clip(shape)
        .background(bg)
        .border(borderWidth, borderColor, shape)
}

fun Modifier.appRow(
    highlighted: Boolean = false,
    paddingValues: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
): Modifier {
    val shape = RoundedCornerShape(AppTheme.radii.sm)
    val bg = if (highlighted) AppColors.surfaceSelected else AppColors.surface
    val border = if (highlighted) AppColors.borderAccent else AppColors.borderSubtle
    return this
        .clip(shape)
        .background(bg)
        .border(1.dp, border, shape)
        .padding(paddingValues)
}

fun Modifier.appPopover(radius: Dp = AppTheme.radii.md): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(AppColors.surfacePopover)
        .border(1.dp, AppColors.borderDefault, shape)
}

fun Modifier.appBadge(
    accent: Color,
    fillAlpha: Float = 0.14f,
    borderAlpha: Float = 0.36f,
    shape: Shape = RoundedCornerShape(AppTheme.radii.xs),
): Modifier = this
    .clip(shape)
    .background(accent.copy(alpha = fillAlpha))
    .border(1.dp, accent.copy(alpha = borderAlpha), shape)

fun pipColor(c: Char): Color = when (c) {
    'W' -> Color(0xFFEDE6C8)
    'U' -> Color(0xFF4A90D9)
    'B' -> Color(0xFF8A7CA8)
    'R' -> Color(0xFFD9534F)
    'G' -> Color(0xFF5FB36A)
    else -> Color(0xFF9AA6A2)
}

fun valueTierColor(value: Double?): Color = when {
    value == null -> Color(0xFF8A9691)
    value >= 80 -> Color(0xFFFFC02E)
    value >= 72 -> Color(0xFF57D670)
    value >= 64 -> Color(0xFF2CC7A6)
    value >= 56 -> Color(0xFF57A6F0)
    value >= 48 -> Color(0xFF93A2AE)
    value >= 40 -> Color(0xFFEC8A3C)
    else -> Color(0xFFE45247)
}

fun isBombTier(value: Double?): Boolean = value != null && value >= 80.0

data class ModelExplain(
    val rank: Int,
    val packSize: Int,
    val soloValue: Double?,
    val ata: Double?,
    val alsa: Double?,
)

fun modelExplainLines(m: ModelExplain): List<String> {
    val rankLine = "Model rank #${m.rank} of ${m.packSize} · without model ${letterGrade(m.soloValue)}"
    val timing = when {
        m.ata != null -> "Drafters usually take it ~pick ${"%.0f".format(m.ata)}"
        m.alsa != null -> "Usually still there ~pick ${"%.0f".format(m.alsa)}"
        else -> null
    }
    return listOfNotNull(rankLine, timing)
}

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
