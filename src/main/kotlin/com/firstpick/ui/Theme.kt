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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    // Raycast Warm Graphite & Frosted Bento hierarchy
    val background = Color(0xFF111114)
    val chrome = Color(0xFF151519)
    val surface = Color(0xFF18181C)
    val surfaceElevated = Color(0xFF202026)
    val surfaceSelected = Color(0xFF27272E)
    val surfacePopover = Color(0xFF1C1C22)

    // Bento & hairline borders
    val borderHairline = Color(0xFF1F1F25)
    val borderSubtle = Color(0xFF27272E)
    val borderDefault = Color(0xFF33333D)
    val borderAccent = Color(0x73FF6363)

    // Zinc typography hierarchy
    val textPrimary = Color(0xFFFAFAFA)
    val textSecondary = Color(0xFFA1A1AA)
    val textMuted = Color(0xFF71717A)
    val textOnAccent = Color(0xFF111114)

    // Semantic accents (Raycast Coral #FF6363 + Amber Gold #F59E0B + Emerald + Rose)
    val accent = Color(0xFFFF6363)
    val accentBright = Color(0xFFF97316)
    val accentSecondary = Color(0xFFF59E0B)
    val positive = Color(0xFF34D399)
    val warning = Color(0xFFFBBF24)
    val danger = Color(0xFFF43F5E)
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
    val xl: Dp = 12.dp
    val pill: Dp = 999.dp
}

@Immutable
object AppTypography {
    val appTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
    )
    val paneTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    )
    val sectionHeader = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp,
    )
    val cardTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    )
    val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )
    val caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Normal,
    )
    val micro = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
    )
    val monoValue = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
    val monoSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    val monoMicro = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
}

object AppTheme {
    val colors: AppColors = AppColors
    val spacing: AppSpacing = AppSpacing
    val radii: AppRadii = AppRadii
    val typography: AppTypography = AppTypography
}

val DraftColorScheme = darkColorScheme(
    primary = AppColors.accent,
    onPrimary = AppColors.textOnAccent,
    primaryContainer = AppColors.surfaceSelected,
    onPrimaryContainer = AppColors.textPrimary,
    secondary = AppColors.accentSecondary,
    onSecondary = AppColors.textOnAccent,
    secondaryContainer = AppColors.surfaceElevated,
    onSecondaryContainer = AppColors.textPrimary,
    tertiary = AppColors.warning,
    onTertiary = AppColors.textOnAccent,
    tertiaryContainer = Color(0xFF2B2210),
    onTertiaryContainer = AppColors.warning,
    background = AppColors.background,
    onBackground = AppColors.textPrimary,
    surface = AppColors.surface,
    onSurface = AppColors.textPrimary,
    surfaceVariant = AppColors.surfaceElevated,
    onSurfaceVariant = AppColors.textSecondary,
    outline = AppColors.borderDefault,
    outlineVariant = AppColors.borderSubtle,
    error = AppColors.danger,
    onError = AppColors.textOnAccent,
    inverseSurface = AppColors.surfacePopover,
    inverseOnSurface = AppColors.textPrimary,
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DraftColorScheme) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides LocalScrollbarStyle.current.copy(
                thickness = 5.dp,
                shape = RoundedCornerShape(AppTheme.radii.pill),
                unhoverColor = AppColors.borderDefault.copy(alpha = 0.65f),
                hoverColor = AppColors.accent,
            ),
            content = content,
        )
    }
}

fun Modifier.appPanel(
    padding: Dp = AppTheme.spacing.lg,
    radius: Dp = AppTheme.radii.xl,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1C1C21),
                    AppColors.surface,
                ),
            ),
        )
        .border(1.dp, AppColors.borderSubtle, shape)
        .padding(padding)
}

fun Modifier.appHeroCard(
    radius: Dp = AppTheme.radii.xl,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    AppColors.accent.copy(alpha = 0.16f),
                    AppColors.accentSecondary.copy(alpha = 0.06f),
                    AppColors.surface,
                ),
                start = Offset(0f, 0f),
                end = Offset(720f, 220f),
            ),
        )
        .border(1.dp, AppColors.accent.copy(alpha = 0.45f), shape)
}

fun Modifier.appTable(
    radius: Dp = AppTheme.radii.xl,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(AppColors.surface)
        .border(1.dp, AppColors.borderSubtle, shape)
}

fun Modifier.appCard(
    selected: Boolean = false,
    active: Boolean = false,
    radius: Dp = AppTheme.radii.xl,
): Modifier {
    val shape = RoundedCornerShape(radius)
    val topColor = when {
        active -> Color(0xFF2B1C20)
        selected -> Color(0xFF241B1E)
        else -> Color(0xFF1C1C21)
    }
    val bottomColor = when {
        active -> Color(0xFF21171A)
        selected -> Color(0xFF1C171A)
        else -> AppColors.surface
    }
    val borderColor = when {
        active -> AppColors.accent
        selected -> AppColors.accent.copy(alpha = 0.55f)
        else -> AppColors.borderSubtle
    }
    val borderWidth = if (active) 1.5.dp else 1.dp
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(topColor, bottomColor)))
        .border(borderWidth, borderColor, shape)
}

fun Modifier.appRow(
    highlighted: Boolean = false,
    accentColor: Color = AppColors.accent,
    paddingValues: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
): Modifier {
    val shape = RoundedCornerShape(AppTheme.radii.md)
    val bg = if (highlighted) {
        Brush.horizontalGradient(
            listOf(
                accentColor.copy(alpha = 0.16f),
                AppColors.surfaceSelected.copy(alpha = 0.65f),
                AppColors.surface,
            ),
        )
    } else {
        Brush.verticalGradient(listOf(AppColors.surface, AppColors.surface))
    }
    val border = if (highlighted) accentColor.copy(alpha = 0.45f) else AppColors.borderSubtle
    return this
        .clip(shape)
        .background(bg)
        .border(1.dp, border, shape)
        .drawBehind {
            if (highlighted) {
                drawLine(
                    color = accentColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        .padding(paddingValues)
}

fun Modifier.appPopover(radius: Dp = AppTheme.radii.lg): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color(0xFF222229),
                    AppColors.surfacePopover,
                ),
            ),
        )
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
    'W' -> Color(0xFFF3EBD3)
    'U' -> Color(0xFF4C9AFF)
    'B' -> Color(0xFFA78BFA)
    'R' -> Color(0xFFF87171)
    'G' -> Color(0xFF34D399)
    else -> Color(0xFF94A3B8)
}

fun valueTierColor(value: Double?): Color = when {
    value == null -> Color(0xFF64748B)
    value >= 80 -> Color(0xFFFBBF24) // A+ Bomb (Amber Gold)
    value >= 72 -> Color(0xFF34D399) // A Great (Emerald)
    value >= 64 -> Color(0xFF2DD4BF) // B+ Strong (Teal)
    value >= 56 -> Color(0xFF60A5FA) // B Good (Sky Blue)
    value >= 48 -> Color(0xFF94A3B8) // C Playable (Slate)
    value >= 40 -> Color(0xFFFB923C) // D Filler (Orange)
    else -> Color(0xFFF43F5E)        // F Weak (Rose)
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

val TierInk = Color(0xFF090A0F)

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
