package com.firstpick.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.advisor.ValueBreakdown

@Composable
fun GradeBadge(value: Double?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val color = valueTierColor(value)
    Column(
        modifier = modifier
            .width(size)
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(9.dp))
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(letterGrade(value), color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(value.asInt(), color = color.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun PipRow(colors: List<Char>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { colors.forEach { Pip(it) } }
}

@Composable
fun Pip(color: Char, size: Dp = 16.dp) {
    val painter = manaPainter(color)
    if (painter != null) {
        Image(painter, contentDescription = color.toString(), modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(pipColor(color)), contentAlignment = Alignment.Center) {
            Text(
                color.toString(),
                fontSize = 10.sp,
                lineHeight = 10.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                color = if (color == 'W') Color(0xFF333333) else Color(0xFF111111),
            )
        }
    }
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
    Box(
        modifier
            .height(10.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
internal fun ColorBar(color: Char, fraction: Double, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pip(color)
        Spacer(Modifier.width(6.dp))
        Bar(fraction.toFloat().coerceIn(0f, 1f), pipColor(color), Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(9.dp))
            Text(trailing, fontFamily = FontFamily.Monospace, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(22.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Panel(title: String, tooltip: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        if (tooltip != null) {
            TooltipArea(
                tooltip = {
                    Surface(
                        modifier = Modifier.padding(4.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = tooltip,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(8.dp).width(200.dp),
                        )
                    }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text("ⓘ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        } else {
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CardPreview(imageUrl: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (imageUrl.isNullOrBlank()) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        modifier = modifier,
        delayMillis = 300,
        tooltip = {
            val bitmap = rememberCardImage(imageUrl)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(232.dp, 323.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        },
        content = content,
    )
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
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun RowScope.MonoCell(
    text: String,
    width: Dp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        modifier = Modifier.width(width),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = weight,
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

@Composable
fun BreakdownTooltip(b: ValueBreakdown, model: ModelExplain? = null) {
    Surface(
        modifier = Modifier.padding(4.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Score breakdown", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.inverseOnSurface)
            Spacer(Modifier.height(8.dp))
            BreakdownRow("Base power", String.format("%.1f", b.baseScore))
            if (b.archetypeShift != 0.0) BreakdownRow("Archetype shift", String.format("%+.1f", b.archetypeShift))
            if (b.synergyBonus != 0.0) BreakdownRow("Synergy bonus", String.format("%+.1f", b.synergyBonus))
            if (b.themeBonus != 0.0) BreakdownRow("Theme synergy", String.format("%+.1f", b.themeBonus))
            if (b.needsPoints != 0.0) BreakdownRow("Deck needs", String.format("%+.1f", b.needsPoints))
            if (b.deckFitPoints != 0.0) BreakdownRow("Deck fit", String.format("%+.1f", b.deckFitPoints))
            if (b.penalty != 0.0) BreakdownRow("Color penalty", String.format("%+.1f", b.penalty))
            if (b.wheelPenalty != 0.0) BreakdownRow("Likely to wheel", String.format("%+.1f", b.wheelPenalty))
            if (b.duplicatePenalty != 0.0) BreakdownRow("Extra copy", String.format("%+.1f", b.duplicatePenalty))
            if (b.scoreCap != 0.0) BreakdownRow(if (b.scoreCap < 0.0) "Capped at 100" else "Floored at 0", String.format("%+.1f", b.scoreCap))
            if (b.modelShift != 0.0) {
                BreakdownRow("Model adjustment", String.format("%+.1f", b.modelShift))
                if (model != null) {
                    Spacer(Modifier.height(3.dp))
                    for (line in modelExplainLines(model)) {
                        Text(
                            line,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                            modifier = Modifier.width(180.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            BreakdownRow("Final score", String.format("%.1f", b.finalScore), bold = true)
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, bold: Boolean = false) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(170.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.inverseOnSurface)
        Text(value, fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = MaterialTheme.colorScheme.inverseOnSurface)
    }
}
