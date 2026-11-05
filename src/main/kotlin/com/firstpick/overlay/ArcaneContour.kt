package com.firstpick.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.exp
import androidx.compose.ui.unit.dp
import com.firstpick.ui.PickHighlight

internal fun arcaneAccent(highlight: PickHighlight): Color =
    if (highlight == PickHighlight.CLEAR) Color(0xFFFFB52E) else Color(0xFF65E8DE)

/** The outer black backing is the same silhouette for normal and legendary cards.
 * Arena's input collider is slightly inset from that backing. The proportional overhang
 * and corner radius were checked against the live pack and enlarged card preview.
 */
internal fun arenaOuterCardContour(rect: GuidanceViewportRect): Path {
    val horizontalOverhang = rect.width * .012f
    val verticalOverhang = rect.height * .015f
    return Path().apply {
        addRoundRect(RoundRect(
            left = rect.left - horizontalOverhang,
            top = rect.top - verticalOverhang,
            right = rect.left + rect.width + horizontalOverhang,
            bottom = rect.top + rect.height + verticalOverhang,
            cornerRadius = CornerRadius(rect.width * .045f),
        ))
    }
}

@Composable
internal fun DraftCardHighlights(marks: List<DraftScoreMark>) {
    Canvas(Modifier.fillMaxSize()) {
        val frames = marks.mapNotNull { mark ->
            mark.bounds.inViewport(size.width, size.height)?.let { rect ->
                Triple(mark, rect, arenaOuterCardContour(rect))
            }
        }
        val artwork = Path().apply { frames.forEach { addPath(it.third) } }
        for ((mark, rect, path) in frames) {
            if (mark.highlight == PickHighlight.NONE) continue
            // Exclude every card's interior, including adjacent unhighlighted cards.
            clipPath(artwork, ClipOp.Difference) {
                drawArcaneAura(path, mark.highlight, rect)
            }
            drawArcaneEdge(path, mark.highlight, rect)
        }
    }
}

private data class ArcanePalette(val shadow: Color, val energy: Color, val gold: Color, val core: Color)

private fun arcanePalette(highlight: PickHighlight) = if (highlight == PickHighlight.CLEAR) {
    ArcanePalette(Color(0xFFC31F28), Color(0xFFFF5C27), Color(0xFFFFD55A), Color(0xFFFFF2C4))
} else {
    ArcanePalette(Color(0xFF168CC1), Color(0xFF36FFE1), Color(0xFFFFE58B), Color(0xFFE6FFFA))
}

private fun ArcanePalette.brush(rect: GuidanceViewportRect): Brush = Brush.linearGradient(
    0f to energy, .48f to energy, .85f to gold, 1f to gold,
    start = Offset(rect.left, rect.top),
    end = Offset(rect.left + rect.width, rect.top + rect.height * .15f),
)

private fun DrawScope.arcaneScale(width: Float) = (width / 220.dp.toPx()).coerceIn(.65f, 2.4f)

private fun DrawScope.drawArcaneAura(path: Path, highlight: PickHighlight, rect: GuidanceViewportRect) {
    val clear = highlight == PickHighlight.CLEAR
    val palette = arcanePalette(highlight)
    val brush = palette.brush(rect)
    val scale = arcaneScale(rect.width)
    val strength = if (clear) 1f else .8f
    val bloom = (if (clear) 66f else 52f).dp.toPx() * scale

    // A broad, soft chromatic bloom surrounds a much brighter, narrow energy band.
    for (step in 28 downTo 1) {
        val t = step / 28f
        drawPath(path, brush, alpha = .11f * strength * exp(-3f * t * t),
            style = Stroke(5.dp.toPx() * scale + bloom * t))
    }
    drawPath(path, brush, alpha = .8f, style = Stroke(8.dp.toPx() * scale))

    val bounds = path.getBounds()
    val left = bounds.left; val right = bounds.right; val top = bounds.top; val bottom = bounds.bottom
    val width = bounds.width; val height = bounds.height
    val reach = 19.dp.toPx() * scale
    // Short, irregular ribbons stay outside the hard card edge, never deforming it.
    val ribbons = listOf(
        Path().apply {
            moveTo(left + width * .23f, top)
            cubicTo(left + width * .28f, top - reach * 1.6f, left + width * .48f, top - reach, left + width * .51f, top)
        },
        Path().apply {
            moveTo(left, top + height * .18f)
            cubicTo(left - reach * 1.4f, top + height * .25f, left - reach * .25f, top + height * .39f, left, top + height * .42f)
        },
        Path().apply {
            moveTo(right, top + height * .36f)
            cubicTo(right + reach * .4f, top + height * .45f, right + reach * 1.2f, top + height * .55f, right, top + height * .66f)
        },
        Path().apply {
            moveTo(left + width * .35f, bottom)
            cubicTo(left + width * .48f, bottom + reach * 1.2f, left + width * .64f, bottom + reach * .8f, left + width * .72f, bottom)
        },
    )
    for (ribbon in ribbons) {
        for (step in 8 downTo 1) {
            drawPath(ribbon, brush, alpha = .035f * strength,
                style = Stroke((1f + step * 1.8f).dp.toPx() * scale))
        }
        drawPath(ribbon, brush, alpha = .7f * strength, style = Stroke(1.1.dp.toPx() * scale))
    }

    val sparks = listOf(
        Offset(left + width * .34f, top - reach * .9f),
        Offset(left + width * .74f, top - reach * .7f),
        Offset(left + width * .81f, top - reach * .32f),
        Offset(left - reach * .56f, top + height * .28f),
        Offset(left - reach * .34f, top + height * .35f),
        Offset(right + reach * .6f, top + height * .53f),
        Offset(right + reach * .32f, top + height * .61f),
        Offset(left + width * .48f, bottom + reach * .61f),
        Offset(left + width * .65f, bottom + reach * .38f),
    )
    for ((index, point) in sparks.withIndex()) {
        val color = if (index % 3 == 0) palette.core else if (point.x > left + width * .55f) palette.gold else palette.energy
        val radius = (if (index % 3 == 0) 2.2f else 1.2f).dp.toPx() * scale
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .65f * strength), Color.Transparent),
            center = point, radius = radius * 5f), radius = radius * 5f, center = point)
        val glint = Path().apply {
            moveTo(point.x, point.y - radius * 2.3f)
            lineTo(point.x + radius * .65f, point.y)
            lineTo(point.x, point.y + radius * 2.3f)
            lineTo(point.x - radius * .65f, point.y)
            close()
        }
        drawPath(glint, color.copy(alpha = .95f))
    }
}

private fun DrawScope.drawArcaneEdge(path: Path, highlight: PickHighlight, rect: GuidanceViewportRect) {
    val palette = arcanePalette(highlight)
    val scale = arcaneScale(rect.width)
    val core = (if (highlight == PickHighlight.CLEAR) 3.2f else 2.6f).dp.toPx() * scale
    drawPath(path, palette.shadow.copy(alpha = .95f), style = Stroke(core + 4.dp.toPx() * scale))
    drawPath(path, palette.brush(rect), style = Stroke(core + 2.6.dp.toPx() * scale))
    drawPath(path, Brush.linearGradient(listOf(palette.core, Color(0xFFFFFAD9)),
        start = Offset(rect.left, rect.top), end = Offset(rect.left + rect.width, rect.top)), style = Stroke(core))
}
