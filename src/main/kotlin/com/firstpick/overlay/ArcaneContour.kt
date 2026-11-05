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
    if (highlight == PickHighlight.CLEAR) Color(0xFFFF3928) else Color(0xFF65E8DE)

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
                    clock.floatValue = (now - origin) / 6_000_000_000f
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
                        drawArcaneAura(path, mark.highlight, rect,
                            (phase * mark.animationSpeed * (if (mark.highlight == PickHighlight.CLEAR) 2.3f else 1f) + mark.animationOffset) % 1f)
                    }
                    drawArcaneEdge(path, mark.highlight, rect)
                }
            }
        }
    })
}

private data class ArcanePalette(val shadow: Color, val energy: Color, val gold: Color, val core: Color)

private fun arcanePalette(highlight: PickHighlight) = if (highlight == PickHighlight.CLEAR) {
    ArcanePalette(Color(0xFFBD1028), Color(0xFFFF3025), Color(0xFFFF552D), Color(0xFFFFBEA3))
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
    val pulse = .88f + .20f * sin(angle)
    val strength = (if (clear) 1f else .8f) * pulse
    val bloom = (if (clear) 78f else 52f).dp.toPx() * scale

    // A broad, soft chromatic bloom surrounds a much brighter, narrow energy band.
    for (step in 28 downTo 1) {
        val t = step / 28f
        drawPath(path, brush, alpha = .11f * strength * exp(-3f * t * t),
            style = Stroke(5.dp.toPx() * scale + bloom * t))
    }
    drawPath(path, brush, alpha = .8f, style = Stroke(8.dp.toPx() * scale))

    if (clear) {
        drawFireWisps(path.getBounds(), scale, phase)
        return
    }
    val bounds = path.getBounds()
    val left = bounds.left; val right = bounds.right; val top = bounds.top; val bottom = bounds.bottom
    val width = bounds.width; val height = bounds.height
    val reach = 19.dp.toPx() * scale * (1f + .30f * sin(angle))
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
        val shimmer = .28f + .72f * (.5f + .5f * sin(localAngle * 2f))
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

/** Flame shapes grow and curl outward, while the underlying card outline stays fixed. */
private fun DrawScope.drawFireWisps(bounds: Rect, scale: Float, phase: Float) {
    val unit = 1.dp.toPx() * scale
    val red = Color(0xFFFF291F)
    val orange = Color(0xFFFF792F)
    val hot = Color(0xFFFFD47B)
    val angle = phase * 2f * PI.toFloat()
    val anchors = buildList {
        for (i in 0 until 6) {
            val y = bounds.top + bounds.height * (.10f + i * .15f + .025f * sin(i * 2.4f))
            add(Offset(bounds.left, y) to Offset(-1f, 0f))
            add(Offset(bounds.right, y) to Offset(1f, 0f))
        }
        for (i in 0 until 4) {
            val x = bounds.left + bounds.width * (.16f + i * .23f + .035f * sin(i * 1.8f))
            add(Offset(x, bounds.top) to Offset(0f, -1f))
            add(Offset(x, bounds.bottom) to Offset(0f, 1f))
        }
    }
    for ((index, entry) in anchors.withIndex()) {
        val (anchor, normal) = entry
        val tangent = Offset(-normal.y, normal.x)
        val life = (phase * 2f + index * .173f) % 1f
        val flare = sin(life * PI.toFloat())
        val height = (3f + (19f + index % 4 * 4f) * flare) * unit
        val width = (3f + (index % 3 + 2f) * flare) * unit
        val curl = sin(angle * 3f + index * 1.31f) * 7f * unit
        val tip = anchor + normal * (height * .6f) + tangent * (curl * .45f) + Offset(0f, -height * .95f)
        val flame = Path().apply {
            val base = anchor - tangent * width
            moveTo(base.x, base.y)
            val c1 = anchor + normal * (height * .35f) - tangent * (width * 1.1f)
            val c2 = tip - normal * (height * .45f) + Offset(0f, height * .25f) + tangent * (curl * .8f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, tip.x, tip.y)
            val c3 = anchor + normal * (height * .65f) + Offset(0f, -height * .3f) + tangent * width
            val c4 = anchor + normal * (height * .08f) + tangent * (width * .15f)
            val end = anchor + tangent * width
            cubicTo(c3.x, c3.y, c4.x, c4.y, end.x, end.y)
            close()
        }
        drawPath(flame, red.copy(alpha = .16f * flare), style = Stroke(11f * unit))
        drawPath(flame, red.copy(alpha = .22f * flare), style = Stroke(5f * unit))
        drawPath(flame, Brush.linearGradient(listOf(red.copy(alpha = .65f), orange.copy(alpha = .8f), hot.copy(alpha = .85f)),
            start = anchor, end = tip), alpha = .35f + .65f * flare)
        val thread = Path().apply {
            moveTo(anchor.x, anchor.y)
            val bend = anchor + normal * (height * .6f) + tangent * (curl * .4f)
            quadraticTo(bend.x, bend.y, tip.x, tip.y)
        }
        drawPath(thread, orange.copy(alpha = .8f * flare), style = Stroke(1.3f * unit))

        // Embers rise and fade to zero before recycling, avoiding abrupt jumps.
        val emberLife = (phase * 2.7f + index * .219f) % 1f
        val emberAlpha = sin(emberLife * PI.toFloat())
        val point = anchor + normal * ((7f + emberLife * 19f) * unit) +
            Offset(sin(angle * 2f + index) * 5f * unit, -emberLife * 34f * unit)
        val radius = (if (index % 3 == 0) 1.6f else .9f) * unit
        drawCircle(Brush.radialGradient(listOf(red.copy(alpha = .65f * emberAlpha), Color.Transparent),
            center = point, radius = radius * 5f), radius = radius * 5f, center = point)
        drawLine(if (index % 3 == 0) hot.copy(alpha = emberAlpha) else orange.copy(alpha = emberAlpha),
            start = point, end = point + Offset(1.1f * unit, -3f * unit), strokeWidth = radius)
    }
}

private fun DrawScope.drawArcaneEdge(path: Path, highlight: PickHighlight, rect: GuidanceViewportRect) {
    val palette = arcanePalette(highlight)
    val scale = arcaneScale(rect.width)
    val core = (if (highlight == PickHighlight.CLEAR) 3.2f else 2.6f).dp.toPx() * scale
    drawPath(path, palette.shadow.copy(alpha = .95f), style = Stroke(core + 4.dp.toPx() * scale))
    drawPath(path, palette.brush(rect), style = Stroke(core + 2.6.dp.toPx() * scale))
    drawPath(path, Brush.linearGradient(listOf(palette.core, if (highlight == PickHighlight.CLEAR) Color(0xFFFFD0B7) else Color(0xFFFFFAD9)),
        start = Offset(rect.left, rect.top), end = Offset(rect.left + rect.width, rect.top)), style = Stroke(core))
}
