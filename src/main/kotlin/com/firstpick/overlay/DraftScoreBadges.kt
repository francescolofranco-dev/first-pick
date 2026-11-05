package com.firstpick.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.firstpick.ui.PickHighlight
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp

/** Exact card IDs and normalized rectangles from the same Arena observation. */
internal data class DraftScoreMark(
    val bounds: NormalizedRect, val value: Double?, val isBest: Boolean,
    val breakdown: com.firstpick.advisor.ValueBreakdown? = null,
    val model: com.firstpick.ui.ModelExplain? = null,
    val gihWr: Double? = null,
    val highlight: PickHighlight = PickHighlight.NONE,
    val slot: Int? = null,
    val covered: Boolean = false,
    val isPreview: Boolean = false,
    val highlightOcclusions: List<NormalizedRect> = emptyList(),
    val animationOffset: Float = 0f,
    val animationSpeed: Float = 1f,
)

@Composable
internal fun DraftScoreBadges(marks: List<DraftScoreMark>, width: Dp, height: Dp) {
    Box(androidx.compose.ui.Modifier.size(width, height)) {
        DraftCardHighlights(marks.filter { !it.covered || it.isPreview })
        for (mark in marks) {
            if (mark.covered) continue
            val rect = scoreBadgeBounds(mark, width.value, height.value) ?: continue
            GradeSeal(mark, rect)
        }
    }
}

internal const val BADGE_SCALE = 0.52f
internal const val BADGE_MIN_SIZE = 48f
internal const val BADGE_MAX_SIZE = 118f
internal const val BADGE_CARD_OVERLAP = 0.86f

@Composable
private fun GradeSeal(m: DraftScoreMark, rect: GuidanceViewportRect) {
    val badge = rect.width
    val cx = rect.left + badge / 2f
    val top = rect.top

    if (m.highlight != PickHighlight.NONE) {
        val haloColor = arcaneAccent(m.highlight)
        val haloD = badge * 1.18f
        val haloCenterY = top + badge * 0.49f
        Box(
            Modifier
                .offset((cx - haloD / 2f).dp, (haloCenterY - haloD / 2f).dp)
                .size(haloD.dp)
                .background(Brush.radialGradient(listOf(haloColor.copy(alpha = if (m.highlight == PickHighlight.CLEAR) 0.45f else 0.18f), Color.Transparent))),
        )
    }

    Box(Modifier.offset((cx - badge / 2f).dp, top.dp)) {
        ScoreBadge(m.value, badge)
    }
}

@Composable
internal fun ScoreBadge(value: Double?, badge: Float) {
    val accent = valueTierColor(value)
    Box(Modifier.size(badge.dp)) {
        PickCrest(accent, Modifier.fillMaxSize())

        if (value == null) {
            Text(
                "—",
                modifier = Modifier.align(Alignment.Center).offset(y = (-0.02f * badge).dp),
                color = accent,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (0.25f * badge).sp,
                lineHeight = (0.25f * badge).sp,
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).offset(y = (-0.025f * badge).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    letterGrade(value),
                    color = accent,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (0.265f * badge).sp,
                    lineHeight = (0.30f * badge).sp,
                    style = androidx.compose.ui.text.TextStyle(shadow = Shadow(accent.copy(alpha = 0.55f), blurRadius = badge * 0.065f)),
                    maxLines = 1,
                )
                Text(
                    value.roundToInt().toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (0.20f * badge).sp,
                    lineHeight = (0.20f * badge).sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PickCrest(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawPickCrest(accent)
    }
}

private fun DrawScope.drawPickCrest(accent: Color) {
    val unit = size.width
    val faceTop = Color(0xFF14191F)
    val faceBottom = Color(0xFF0E1217)
    val rimLight = lerp(accent, Color.White, 0.55f)

    // The glow stays within the existing square footprint, including at the
    // minimum badge size. No offscreen blur layers are needed per Arena card.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0.09f), Color.Transparent),
            center = Offset(unit * 0.5f, unit * 0.48f), radius = unit * 0.48f,
        ),
        radius = unit * 0.48f,
        center = Offset(unit * 0.5f, unit * 0.48f),
    )

    fun card(left: Float, top: Float, width: Float, height: Float, degrees: Float, front: Boolean) {
        val origin = Offset(unit * left, unit * top)
        val cardSize = Size(unit * width, unit * height)
        val radius = CornerRadius(unit * 0.065f)
        val pivot = origin + Offset(cardSize.width / 2f, cardSize.height / 2f)
        rotate(degrees, pivot) {
            // Layered low-alpha strokes give the rim a restrained bloom.
            for ((stroke, alpha) in listOf(0.075f to 0.035f, 0.050f to 0.06f, 0.030f to 0.12f)) {
                drawRoundRect(accent.copy(alpha = alpha), origin, cardSize, radius,
                    style = Stroke(unit * stroke))
            }
            drawRoundRect(
                brush = Brush.verticalGradient(
                    if (front) listOf(faceTop, faceBottom)
                    else listOf(lerp(faceTop, accent, 0.18f).copy(alpha = 0.92f), faceBottom.copy(alpha = 0.88f)),
                    startY = origin.y, endY = origin.y + cardSize.height,
                ),
                topLeft = origin, size = cardSize, cornerRadius = radius,
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(rimLight.copy(alpha = if (front) 1f else 0.72f), accent.copy(alpha = if (front) 1f else 0.60f)),
                    startY = origin.y, endY = origin.y + cardSize.height,
                ),
                topLeft = origin, size = cardSize, cornerRadius = radius,
                style = Stroke(unit * if (front) 0.021f else 0.016f),
            )
            val inset = unit * 0.026f
            drawRoundRect(
                color = accent.copy(alpha = if (front) 0.42f else 0.22f),
                topLeft = origin + Offset(inset, inset),
                size = Size(cardSize.width - inset * 2, cardSize.height - inset * 2),
                cornerRadius = CornerRadius(unit * 0.043f), style = Stroke(unit * 0.007f),
            )
        }
    }

    card(0.15f, 0.22f, 0.39f, 0.62f, -12f, front = false)
    card(0.46f, 0.22f, 0.39f, 0.62f, 12f, front = false)
    card(0.235f, 0.07f, 0.53f, 0.80f, 0f, front = true)
}
