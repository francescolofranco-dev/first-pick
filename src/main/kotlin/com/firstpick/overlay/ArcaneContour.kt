package com.firstpick.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithCache
import kotlinx.coroutines.isActive
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import kotlin.math.sin
import kotlin.math.PI
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
internal fun DraftCardHighlights(marks: List<DraftScoreMark>, animationPhase: Float? = null) {
    val clock = remember { mutableFloatStateOf(0f) }
    val active = animationPhase == null && marks.any { it.highlight != PickHighlight.NONE }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var origin = 0L
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (origin == 0L) origin = now
                // Cap repainting at 30 fps; reading the clock in draw avoids recomposition/layout.
                if (last == 0L || now - last >= 33_333_333L) {
                    clock.floatValue = ((now - origin) % 6_000_000_000L) / 6_000_000_000f
                    last = now
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().drawWithCache {
        val frames = marks.mapNotNull { mark ->
            mark.bounds.inViewport(size.width, size.height)?.let { rect ->
                Triple(mark, rect, arenaOuterCardContour(rect))
            }
        }
        val artwork = Path().apply { frames.forEach { addPath(it.third) } }
        val blockers = frames.associate { (mark, _, path) ->
            path to Path().apply {
                mark.highlightOcclusions.forEach { rect ->
                    addRect(Rect((rect.x * size.width).toFloat(), (rect.y * size.height).toFloat(),
                        (rect.right * size.width).toFloat(), (rect.bottom * size.height).toFloat()))
                }
            }
        }
        onDrawBehind {
            val phase = animationPhase ?: clock.floatValue
            for ((mark, rect, path) in frames) {
                if (mark.highlight == PickHighlight.NONE) continue
                // A tooltip can cover a section of a preview without hiding its entire aura.
                clipPath(blockers.getValue(path), ClipOp.Difference) {
                    clipPath(artwork, ClipOp.Difference) {
                        drawArcaneAura(path, mark.highlight, rect, phase)
                    }
                    drawArcaneEdge(path, mark.highlight, rect)
                }
            }
        }
    })
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

private fun DrawScope.drawArcaneAura(path: Path, highlight: PickHighlight, rect: GuidanceViewportRect, phase: Float) {
    val clear = highlight == PickHighlight.CLEAR
    val palette = arcanePalette(highlight)
    val brush = palette.brush(rect)
    val scale = arcaneScale(rect.width)
    val angle = phase * (2f * PI.toFloat())
    val pulse = .92f + .08f * sin(angle)
    val strength = (if (clear) 1f else .8f) * pulse
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
    val reach = 19.dp.toPx() * scale * (1f + .18f * sin(angle))
    val drift = .025f * sin(angle)
    // Short, irregular ribbons stay outside the hard card edge, never deforming it.
    val ribbons = listOf(
        Path().apply {
            moveTo(left + width * (.23f + drift), top)
            cubicTo(left + width * .28f, top - reach * 1.6f, left + width * .48f, top - reach, left + width * (.51f + drift), top)
        },
        Path().apply {
            moveTo(left, top + height * (.18f + drift))
            cubicTo(left - reach * 1.4f, top + height * .25f, left - reach * .25f, top + height * .39f, left, top + height * (.42f + drift))
        },
        Path().apply {
            moveTo(right, top + height * (.36f - drift))
            cubicTo(right + reach * .4f, top + height * .45f, right + reach * 1.2f, top + height * .55f, right, top + height * (.66f - drift))
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
    for ((index, anchor) in sparks.withIndex()) {
        val localAngle = angle + index * 1.7f
        val shimmer = .48f + .52f * (.5f + .5f * sin(localAngle * 2f))
        val point = anchor + Offset(sin(localAngle) * 3.dp.toPx() * scale,
            sin(localAngle + 1.2f) * 4.dp.toPx() * scale)
        val color = if (index % 3 == 0) palette.core else if (point.x > left + width * .55f) palette.gold else palette.energy
        val radius = (if (index % 3 == 0) 2.2f else 1.2f).dp.toPx() * scale
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = .65f * strength * shimmer), Color.Transparent),
            center = point, radius = radius * 5f), radius = radius * 5f, center = point)
        val glint = Path().apply {
            moveTo(point.x, point.y - radius * 2.3f)
            lineTo(point.x + radius * .65f, point.y)
            lineTo(point.x, point.y + radius * 2.3f)
            lineTo(point.x - radius * .65f, point.y)
            close()
        }
        drawPath(glint, color.copy(alpha = .95f * shimmer))
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
