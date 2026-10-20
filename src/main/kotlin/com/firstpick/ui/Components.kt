package com.firstpick.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.firstpick.advisor.ValueBreakdown
import kotlin.math.roundToInt

@Composable
internal fun AppBrandMark(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFFF6363), Color(0xFFF59E0B)),
                    start = Offset(0f, 0f),
                    end = Offset(64f, 64f),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            rotate(degrees = -9f, pivot = Offset(w * 0.44f, h * 0.54f)) {
                drawRoundRect(
                    color = Color(0xFF111114).copy(alpha = 0.24f),
                    topLeft = Offset(w * 0.22f, h * 0.22f),
                    size = Size(w * 0.34f, h * 0.54f),
                    cornerRadius = CornerRadius(w * 0.06f),
                )
            }
        }
        Text(
            text = "FP",
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.4).sp,
            color = Color(0xFFFFFFFF),
        )
    }
}

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
    val shape = RoundedCornerShape(AppTheme.radii.md)
    val bgBrush = when {
        !enabled -> Brush.verticalGradient(listOf(AppTheme.colors.surface, AppTheme.colors.surface))
        variant == AppButtonVariant.Primary -> Brush.verticalGradient(
            listOf(AppTheme.colors.accent, AppTheme.colors.accentBright),
        )
        active || variant == AppButtonVariant.Tonal -> Brush.verticalGradient(
            listOf(AppTheme.colors.accent.copy(alpha = 0.20f), AppTheme.colors.accent.copy(alpha = 0.12f)),
        )
        variant == AppButtonVariant.Warning -> Brush.verticalGradient(
            listOf(AppTheme.colors.warning.copy(alpha = 0.20f), AppTheme.colors.warning.copy(alpha = 0.12f)),
        )
        else -> Brush.verticalGradient(
            listOf(Color(0xFF25252C), AppTheme.colors.surfaceElevated),
        )
    }
    val borderColor = when {
        !enabled -> AppTheme.colors.borderSubtle
        variant == AppButtonVariant.Primary -> AppTheme.colors.accent
        active || variant == AppButtonVariant.Tonal -> AppTheme.colors.accent.copy(alpha = 0.45f)
        variant == AppButtonVariant.Warning -> AppTheme.colors.warning.copy(alpha = 0.45f)
        else -> AppTheme.colors.borderDefault
    }
    Row(
        modifier = modifier
            .heightIn(min = 27.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clip(shape)
            .background(bgBrush)
            .border(1.dp, borderColor, shape)
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
    val badge: String? = null,
    val shortcut: String? = null,
    val selected: Boolean,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
internal fun AppSegmentedTabs(
    tabs: List<SegmentedTabItem>,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    Row(
        modifier = modifier.height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in tabs) {
            val itemShape = RoundedCornerShape(AppTheme.radii.md)
            val tabModifier = if (fillWidth) Modifier.weight(1f) else Modifier
            Row(
                modifier = tabModifier
                    .fillMaxHeight()
                    .alpha(if (tab.enabled) 1f else 0.38f)
                    .clip(itemShape)
                    .background(
                        if (tab.selected) {
                            Brush.verticalGradient(
                                listOf(Color(0xFF2D2D36), AppTheme.colors.surfaceSelected),
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Transparent),
                            )
                        },
                    )
                    .then(
                        if (tab.selected) {
                            Modifier.border(1.dp, Color(0xFF3F3F48), itemShape)
                        } else {
                            Modifier
                        },
                    )
                    .semantics {
                        selected = tab.selected
                        stateDescription = when {
                            !tab.enabled -> "Disabled"
                            tab.selected -> "Selected"
                            else -> "Not selected"
                        }
                    }
                    .focusable(tab.enabled)
                    .clickable(
                        enabled = tab.enabled,
                        role = Role.Tab,
                        onClick = tab.onClick,
                    )
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = tab.label,
                    fontSize = 11.sp,
                    fontWeight = if (tab.selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (tab.selected) AppTheme.colors.textPrimary else AppTheme.colors.textSecondary,
                    maxLines = 1,
                )
                if (tab.badge != null) {
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (tab.selected) AppTheme.colors.accent.copy(alpha = 0.22f)
                                else AppTheme.colors.surfaceElevated,
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = tab.badge,
                            style = AppTheme.typography.monoMicro,
                            color = if (tab.selected) AppTheme.colors.accent else AppTheme.colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                } else if (tab.shortcut != null) {
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (tab.selected) AppTheme.colors.background else Color.Transparent)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = tab.shortcut,
                            style = AppTheme.typography.monoMicro,
                            color = if (tab.selected) AppTheme.colors.textSecondary else AppTheme.colors.textMuted,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GradeBadge(value: Double?, modifier: Modifier = Modifier) {
    val c = valueTierColor(value)
    val grade = letterGrade(value)
    val shape = RoundedCornerShape(AppTheme.radii.sm)
    Box(
        modifier
            .width(38.dp)
            .height(34.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(c.copy(alpha = 0.22f), c.copy(alpha = 0.08f)),
                ),
            )
            .border(1.dp, c.copy(alpha = 0.50f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (value == null) {
            Text(
                grade,
                color = c,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                lineHeight = 13.sp,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    grade,
                    color = c,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    lineHeight = 13.sp,
                )
                Text(
                    value.roundToInt().toString(),
                    color = AppTheme.colors.textPrimary.copy(alpha = 0.90f),
                    style = AppTheme.typography.monoMicro.copy(fontSize = 9.sp, lineHeight = 10.sp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AppTooltip(
    tooltip: String,
    title: String? = null,
    width: Dp = 236.dp,
    delayMillis: Int = 160,
    content: @Composable () -> Unit,
) {
    TooltipArea(
        delayMillis = delayMillis,
        tooltip = {
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .appPopover(radius = AppTheme.radii.lg)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .width(width),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            title.uppercase(),
                            style = AppTheme.typography.sectionHeader,
                            color = AppTheme.colors.accent,
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.borderSubtle))
                    }
                    Text(
                        tooltip,
                        style = AppTheme.typography.caption,
                        color = AppTheme.colors.textPrimary,
                    )
                }
            }
        },
        content = content,
    )
}

internal fun reasonChipTooltip(reason: String): String {
    val lower = reason.lowercase()
    return when {
        "bomb" in lower -> "Premium game-winning card that can take over a match on its own."
        "finisher" in lower -> "High-impact threat that helps your deck close out stalled board states."
        "removal" in lower -> "Interaction spell that answers opposing creatures or permanents."
        "fixing" in lower -> "Helps cast your second color or splash cards reliably."
        "synergy" in lower || "archetype" in lower || "glue" in lower -> "Synergizes directly with your drafted archetype theme and cards already in your pool."
        "on-color" in lower -> "Matches your active color lane and can be cast without straining your mana base."
        "off-color" in lower -> "Outside your current color lane — requires pivoting or splashing to play."
        "2-drop" in lower || "early" in lower || "cheap" in lower -> "Supports your early mana curve so you don't fall behind on board."
        "combat trick" in lower -> "Instant-speed buff that wins combat exchanges and pushes damage."
        "evasion" in lower -> "Hard-to-block threat (such as flying or menace) that pressures life totals."
        "wheel" in lower -> "Often seen late in packs — you may be able to pass it now and take it on the wheel."
        else -> "Scoring factor contributing to this recommendation."
    }
}

@Composable
internal fun ReasonChip(reason: String, tooltip: String? = reasonChipTooltip(reason)) {
    val lower = reason.lowercase()
    val accent = when {
        "bomb" in lower || "finisher" in lower -> AppTheme.colors.warning
        "removal" in lower || "efficient" in lower -> AppTheme.colors.danger
        "synergy" in lower || "archetype" in lower || "glue" in lower -> AppTheme.colors.accent
        "on-color" in lower || "fixing" in lower || "pair" in lower -> AppTheme.colors.positive
        "off-color" in lower || "unrated" in lower || "restricted" in lower -> AppTheme.colors.textMuted
        else -> AppTheme.colors.accentSecondary
    }
    val shape = RoundedCornerShape(AppTheme.radii.xs)
    val chipContent = @Composable {
        Box(
            Modifier
                .clip(shape)
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.34f), shape)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                text = reason,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (accent == AppTheme.colors.textMuted) AppTheme.colors.textSecondary else accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (!tooltip.isNullOrBlank()) {
        AppTooltip(tooltip = tooltip, title = reason) {
            chipContent()
        }
    } else {
        chipContent()
    }
}

@Composable
internal fun WinRateMeter(
    gihWr: Double?,
    modifier: Modifier = Modifier,
) {
    val pctText = gihWr.asPct()
    val fraction = if (gihWr == null || gihWr.isNaN()) {
        0f
    } else {
        ((gihWr - 0.50) / 0.15).toFloat().coerceIn(0.08f, 1f)
    }
    val barColor = when {
        gihWr == null -> AppTheme.colors.borderDefault
        gihWr >= 0.60 -> AppTheme.colors.accent
        gihWr >= 0.57 -> AppTheme.colors.positive
        gihWr >= 0.54 -> AppTheme.colors.accentSecondary
        else -> AppTheme.colors.textMuted
    }
    AppTooltip(
        title = "GIH Win Rate",
        tooltip = "17Lands Games in Hand win rate — how often players win games when this card is drawn or in their opening hand.",
    ) {
        Column(
            modifier = modifier.width(48.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (gihWr != null) "$pctText%" else "—",
                style = AppTheme.typography.monoSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (gihWr != null) AppTheme.colors.textPrimary else AppTheme.colors.textMuted,
                maxLines = 1,
            )
            Box(
                Modifier
                    .width(38.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(AppTheme.colors.surfaceElevated),
            ) {
                if (gihWr != null) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(99.dp))
                            .background(barColor),
                    )
                }
            }
        }
    }
}

internal fun manaColorName(color: Char): String = when (color.uppercaseChar()) {
    'W' -> "White mana"
    'U' -> "Blue mana"
    'B' -> "Black mana"
    'R' -> "Red mana"
    'G' -> "Green mana"
    'C' -> "Colorless mana"
    else -> "Colorless mana"
}

@Composable
internal fun Pip(color: Char, size: Dp = 15.dp) {
    val painter = manaPainter(color.uppercaseChar())
    if (painter != null) {
        Image(painter, contentDescription = manaColorName(color), modifier = Modifier.size(size))
    } else {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(50))
                .background(pipColor(color))
                .semantics { contentDescription = manaColorName(color) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                color.toString(),
                fontSize = 9.sp,
                lineHeight = 9.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                color = TierInk,
            )
        }
    }
}

@Composable
private fun manaPainter(color: Char): Painter? {
    val density = LocalDensity.current
    return remember(color, density) {
        runCatching { useResource("symbols/$color.svg") { loadSvgPainter(it, density) } }.getOrNull()
    }
}

@Composable
internal fun PipRow(colors: List<Char>, size: Dp = 15.dp) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in colors) Pip(c, size = size)
    }
}

@Composable
internal fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val safeFraction = safeFloatFraction(fraction)
    val shape = RoundedCornerShape(AppTheme.radii.pill)
    Box(
        modifier
            .height(5.dp)
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
        Pip(color, size = 14.dp)
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
internal fun Panel(
    title: String,
    tooltip: String? = null,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .appPanel(padding = 11.dp, radius = AppTheme.radii.xl),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (tooltip != null) {
                AppTooltip(
                    title = title,
                    tooltip = tooltip,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics {
                            contentDescription = "$title: $tooltip"
                        },
                    ) {
                        Text(
                            title.uppercase(),
                            style = AppTheme.typography.sectionHeader,
                            color = AppTheme.colors.textSecondary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "ⓘ",
                            fontSize = 10.sp,
                            color = AppTheme.colors.textMuted,
                        )
                    }
                }
            } else {
                Text(
                    title.uppercase(),
                    style = AppTheme.typography.sectionHeader,
                    color = AppTheme.colors.textSecondary,
                )
            }
            if (trailing != null) {
                Text(
                    trailing,
                    style = AppTheme.typography.monoMicro,
                    color = AppTheme.colors.textMuted,
                )
            }
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
            .appPopover(radius = AppTheme.radii.lg)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.width(216.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SCORE BREAKDOWN",
                    style = AppTheme.typography.sectionHeader,
                    color = AppTheme.colors.textSecondary,
                )
                Text(
                    letterGrade(b.finalScore),
                    style = AppTheme.typography.monoSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = valueTierColor(b.finalScore),
                )
            }
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
                            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(AppTheme.radii.xs))
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
        delta > 0.0 -> AppTheme.colors.positive
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
