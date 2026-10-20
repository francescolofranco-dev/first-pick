package com.firstpick.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.firstpick.advisor.ValueBreakdown

internal enum class AppButtonVariant {
    Primary,
    Tonal,
    Subtle,
    Warning,
}

@Composable
internal fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: AppButtonVariant = AppButtonVariant.Subtle,
    active: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(AppTheme.radii.sm)
    val bg = when {
        !enabled -> AppTheme.colors.surface
        variant == AppButtonVariant.Primary -> AppTheme.colors.accent
        active || variant == AppButtonVariant.Tonal -> AppTheme.colors.surfaceSelected
        variant == AppButtonVariant.Warning -> AppTheme.colors.warning.copy(alpha = 0.12f)
        else -> AppTheme.colors.surfaceElevated
    }
    val borderColor = when {
        !enabled -> AppTheme.colors.borderSubtle
        variant == AppButtonVariant.Primary -> AppTheme.colors.accent
        active || variant == AppButtonVariant.Tonal -> AppTheme.colors.borderAccent
        variant == AppButtonVariant.Warning -> AppTheme.colors.warning.copy(alpha = 0.42f)
        else -> AppTheme.colors.borderDefault
    }
    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .semantics { role = Role.Button }
            .focusable(enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

internal data class SegmentedTabItem(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
internal fun AppSegmentedTabs(
    tabs: List<SegmentedTabItem>,
    modifier: Modifier = Modifier,
) {
    val outerShape = RoundedCornerShape(AppTheme.radii.md)
    val innerShape = RoundedCornerShape(AppTheme.radii.sm)
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(outerShape)
            .background(AppTheme.colors.surface)
            .border(1.dp, AppTheme.colors.borderSubtle, outerShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .alpha(if (tab.enabled) 1f else 0.42f)
                    .clip(innerShape)
                    .background(if (tab.selected) AppTheme.colors.surfaceElevated else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (tab.selected) AppTheme.colors.borderDefault else Color.Transparent,
                        shape = innerShape,
                    )
                    .semantics {
                        role = Role.Tab
                        selected = tab.selected
                    }
                    .focusable(tab.enabled)
                    .clickable(enabled = tab.enabled, role = Role.Tab, onClick = tab.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    fontSize = 12.sp,
                    fontWeight = if (tab.selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (tab.selected) AppTheme.colors.accent else AppTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun GradeBadge(value: Double?, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val color = valueTierColor(value)
    val shape = RoundedCornerShape(AppTheme.radii.sm)
    Column(
        modifier = modifier
            .width(size)
            .clip(shape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.42f), shape)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            letterGrade(value),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            lineHeight = 15.sp,
        )
        Text(
            value.asInt(),
            color = color.copy(alpha = 0.85f),
            style = AppTheme.typography.monoMicro,
        )
    }
}

@Composable
fun PipRow(colors: List<Char>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { Pip(it) }
    }
}

@Composable
fun Pip(color: Char, size: Dp = 15.dp) {
    val painter = manaPainter(color)
    if (painter != null) {
        Image(painter, contentDescription = manaColorName(color), modifier = Modifier.size(size))
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(50)).background(pipColor(color)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                color.toString(),
                fontSize = 9.sp,
                lineHeight = 9.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                color = if (color == 'W') Color(0xFF333333) else Color(0xFF111111),
            )
        }
    }
}

internal fun manaColorName(color: Char): String = when (color) {
    'W' -> "White mana"
    'U' -> "Blue mana"
    'B' -> "Black mana"
    'R' -> "Red mana"
    'G' -> "Green mana"
    else -> "Colorless mana"
}

@Composable
private fun manaPainter(color: Char): Painter? {
    val density = LocalDensity.current
    return remember(color) {
        runCatching { useResource("symbols/$color.svg") { loadSvgPainter(it, density) } }.getOrNull()
    }
}

@Composable
internal fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val safeFraction = safeFloatFraction(fraction)
    val shape = RoundedCornerShape(AppTheme.radii.xs)
    Box(
        modifier
            .height(6.dp)
            .clip(shape)
            .background(AppTheme.colors.surfaceElevated),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(safeFraction)
                .clip(shape)
                .background(color),
        )
    }
}

@Composable
internal fun ColorBar(color: Char, fraction: Double, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pip(color)
        Spacer(Modifier.width(6.dp))
        Bar(safeFloatFraction(fraction.toFloat()), pipColor(color), Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                style = AppTheme.typography.monoSmall,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(22.dp),
            )
        }
    }
}

internal fun safeFloatFraction(fraction: Float): Float =
    if (fraction.isNaN() || fraction.isInfinite()) 0f else fraction.coerceIn(0f, 1f)

internal fun safeScoreFraction(score: Double, maxScore: Double): Double =
    if (maxScore <= 0.0 || score.isNaN() || maxScore.isNaN() || score.isInfinite() || maxScore.isInfinite()) {
        0.0
    } else {
        (score / maxScore).coerceIn(0.0, 1.0)
    }

internal fun archetypeFraction(winRate: Double, minWinRate: Double, maxWinRate: Double): Float =
    if (maxWinRate <= minWinRate || winRate.isNaN() || minWinRate.isNaN() || maxWinRate.isNaN()) {
        1.0f
    } else {
        (0.15 + 0.85 * (winRate - minWinRate) / (maxWinRate - minWinRate)).toFloat().coerceIn(0f, 1f)
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Panel(title: String, tooltip: String? = null, content: @Composable () -> Unit) {
    var helpExpanded by remember(title, tooltip) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .appPanel(padding = AppTheme.spacing.lg, radius = AppTheme.radii.lg),
    ) {
        if (tooltip != null) {
            TooltipArea(
                tooltip = {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .appPopover()
                            .padding(10.dp)
                            .width(210.dp),
                    ) {
                        Text(
                            text = tooltip,
                            color = AppTheme.colors.textPrimary,
                            style = AppTheme.typography.caption,
                        )
                    }
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .semantics {
                            role = Role.Button
                            contentDescription = "Help for $title"
                            stateDescription = if (helpExpanded) "Help expanded" else "Help collapsed"
                        }
                        .focusable()
                        .clickable(role = Role.Button) { helpExpanded = !helpExpanded },
                ) {
                    Text(
                        title.uppercase(),
                        style = AppTheme.typography.sectionHeader,
                        color = AppTheme.colors.textSecondary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "ⓘ",
                        fontSize = 11.sp,
                        color = AppTheme.colors.textMuted,
                    )
                }
            }
            if (helpExpanded) {
                Spacer(Modifier.height(6.dp))
                Text(tooltip, style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
            }
        } else {
            Text(
                title.uppercase(),
                style = AppTheme.typography.sectionHeader,
                color = AppTheme.colors.textSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardPreview(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    description: String = "Card",
    content: @Composable () -> Unit,
) {
    if (imageUrl.isNullOrBlank()) {
        Box(modifier) { content() }
        return
    }
    var pinned by remember(imageUrl) { mutableStateOf(false) }
    val preview: @Composable () -> Unit = {
        val bitmap = rememberCardImage(imageUrl)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$description card art",
                modifier = Modifier.size(232.dp, 323.dp).clip(RoundedCornerShape(AppTheme.radii.xl)),
            )
        }
    }
    Box(modifier) {
        TooltipArea(
            delayMillis = 300,
            tooltip = preview,
        ) {
            Box(
                Modifier
                    .semantics {
                        role = Role.Button
                        contentDescription = "Show $description card art"
                        stateDescription = if (pinned) "Preview open" else "Preview closed"
                    }
                    .focusable()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (pinned) "Close card preview" else "Open card preview",
                    ) { pinned = !pinned },
            ) {
                content()
            }
        }
        if (pinned) {
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { pinned = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(AppTheme.radii.xl),
                    shadowElevation = 10.dp,
                    modifier = Modifier
                        .semantics { contentDescription = "$description card preview. Press Escape to close." }
                        .clickable(role = Role.Button, onClickLabel = "Close card preview") { pinned = false },
                ) {
                    preview()
                }
            }
        }
    }
}

@Composable
private fun rememberCardImage(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    return produceState<ImageBitmap?>(initialValue = null, url) {
        value = CardImageLoader.load(url)
    }.value
}

@Composable
internal fun RowScope.HeaderCell(text: String, width: Dp? = null, weight: Float? = null) {
    Text(
        text,
        modifier = cellModifier(width, weight),
        style = AppTheme.typography.micro,
        color = AppTheme.colors.textSecondary,
    )
}

@Composable
internal fun RowScope.MonoCell(
    text: String,
    width: Dp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = AppTheme.colors.textPrimary,
) {
    Text(
        text,
        modifier = Modifier.width(width),
        style = AppTheme.typography.monoValue.copy(fontWeight = weight),
        color = color,
    )
}

internal fun RowScope.cellModifier(width: Dp?, weight: Float?): Modifier = when {
    weight != null -> Modifier.weight(weight)
    width != null -> Modifier.width(width)
    else -> Modifier
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

internal fun Double?.asPct(): String = this?.let { "%.1f".format(it * 100) } ?: "—"
internal fun Double?.as1dp(): String = this?.let { "%.1f".format(it) } ?: "—"
internal fun Double?.asInt(): String = this?.let { "%.0f".format(it) } ?: "—"

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScoreBreakdownTrigger(
    breakdown: ValueBreakdown,
    model: ModelExplain?,
    content: @Composable () -> Unit,
) {
    var expanded by remember(breakdown, model) { mutableStateOf(false) }
    Box {
        TooltipArea(tooltip = { BreakdownTooltip(breakdown, model) }) {
            Box(
                Modifier
                    .semantics {
                        role = Role.Button
                        contentDescription = "Show score breakdown"
                        stateDescription = if (expanded) "Score breakdown open" else "Score breakdown closed"
                    }
                    .focusable()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Close score breakdown" else "Open score breakdown",
                    ) { expanded = !expanded },
            ) {
                content()
            }
        }
        if (expanded) {
            Popup(
                alignment = Alignment.Center,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Box(
                    Modifier.clickable(role = Role.Button, onClickLabel = "Close score breakdown") {
                        expanded = false
                    },
                ) {
                    BreakdownTooltip(breakdown, model)
                }
            }
        }
    }
}

@Composable
fun BreakdownTooltip(b: ValueBreakdown, model: ModelExplain? = null) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .appPopover(radius = AppTheme.radii.md)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "SCORE BREAKDOWN",
                style = AppTheme.typography.sectionHeader,
                color = AppTheme.colors.textSecondary,
            )
            Box(Modifier.width(216.dp).height(1.dp).background(AppTheme.colors.borderSubtle))
            Spacer(Modifier.height(1.dp))
            BreakdownRow("Base power", String.format("%.1f", b.baseScore))
            if (b.archetypeShift != 0.0) BreakdownRow("Archetype shift", String.format("%+.1f", b.archetypeShift), delta = b.archetypeShift)
            if (b.synergyBonus != 0.0) BreakdownRow("Synergy bonus", String.format("%+.1f", b.synergyBonus), delta = b.synergyBonus)
            if (b.themeBonus != 0.0) BreakdownRow("Theme synergy", String.format("%+.1f", b.themeBonus), delta = b.themeBonus)
            if (b.needsPoints != 0.0) BreakdownRow("Deck needs", String.format("%+.1f", b.needsPoints), delta = b.needsPoints)
            if (b.deckFitPoints != 0.0) BreakdownRow("Deck fit", String.format("%+.1f", b.deckFitPoints), delta = b.deckFitPoints)
            if (b.penalty != 0.0) BreakdownRow("Color penalty", String.format("%+.1f", b.penalty), delta = b.penalty)
            if (b.wheelPenalty != 0.0) BreakdownRow("Likely to wheel", String.format("%+.1f", b.wheelPenalty), delta = b.wheelPenalty)
            if (b.duplicatePenalty != 0.0) BreakdownRow("Extra copy", String.format("%+.1f", b.duplicatePenalty), delta = b.duplicatePenalty)
            if (b.guideAdjustment != 0.0) BreakdownRow("Guide constraint", String.format("%+.1f", b.guideAdjustment), delta = b.guideAdjustment)
            if (b.scoreCap != 0.0) BreakdownRow(if (b.scoreCap < 0.0) "Capped at 100" else "Floored at 0", String.format("%+.1f", b.scoreCap), delta = b.scoreCap)
            if (b.modelShift != 0.0) {
                BreakdownRow("Model adjustment", String.format("%+.1f", b.modelShift), delta = b.modelShift)
                if (model != null) {
                    Column(
                        Modifier
                            .width(216.dp)
                            .clip(RoundedCornerShape(AppTheme.radii.xs))
                            .background(AppTheme.colors.surfaceElevated)
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (line in modelExplainLines(model)) {
                            Text(
                                line,
                                style = AppTheme.typography.micro,
                                color = AppTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(1.dp))
            Box(Modifier.width(216.dp).height(1.dp).background(AppTheme.colors.borderSubtle))
            BreakdownRow("Final score", String.format("%.1f", b.finalScore), bold = true)
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, bold: Boolean = false, delta: Double = 0.0) {
    val valueColor = when {
        bold -> AppTheme.colors.accent
        delta > 0.0 -> AppTheme.colors.accent
        delta < 0.0 -> AppTheme.colors.danger
        else -> AppTheme.colors.textPrimary
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.width(216.dp),
    ) {
        Text(
            label,
            style = AppTheme.typography.caption,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (bold) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary,
        )
        Text(
            value,
            style = AppTheme.typography.monoSmall,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor,
        )
    }
}
