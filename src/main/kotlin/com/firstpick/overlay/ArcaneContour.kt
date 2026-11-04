package com.firstpick.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
        for (mark in marks) {
            if (mark.highlight == PickHighlight.NONE || !mark.bounds.isValid()) continue
            val rect = mark.bounds.inViewport(size.width, size.height) ?: continue
            drawArcaneContour(arenaOuterCardContour(rect), mark.highlight, rect.width)
        }
    }
}

private fun DrawScope.drawArcaneContour(path: Path, highlight: PickHighlight, cardWidth: Float) {
    val clear = highlight == PickHighlight.CLEAR
    val accent = arcaneAccent(highlight)
    val scale = (cardWidth / 220.dp.toPx()).coerceIn(.6f, 1.8f)
    val core = (if (clear) 2.2f else 1.6f).dp.toPx() * scale
    val bloom = (if (clear) 22f else 15f).dp.toPx() * scale

    // All bloom is outside the fitted frame. Artwork and rules text remain untouched.
    clipPath(path, ClipOp.Difference) {
        for (step in 20 downTo 1) {
            val t = step / 20f
            drawPath(path, accent.copy(alpha = (if (clear) .065f else .045f) * (1f - t * .7f)),
                style = Stroke(core + bloom * t * t))
        }
    }
    drawPath(path, Color(0xFF17110B).copy(alpha = .92f), style = Stroke(core + 3.4.dp.toPx() * scale))
    drawPath(path, accent, style = Stroke(core + 2.dp.toPx() * scale))
    drawPath(path, if (clear) Color(0xFFFFF3C9) else Color(0xFFD9FFFA), style = Stroke(core))
}
