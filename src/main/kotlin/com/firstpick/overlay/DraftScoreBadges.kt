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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.ui.isBombTier
import com.firstpick.ui.letterGrade
import com.firstpick.ui.valueTierColor
import kotlin.math.roundToInt
import androidx.compose.ui.unit.Dp

/** Exact card IDs and normalized rectangles from the same Arena observation. */
internal data class DraftScoreMark(
    val bounds: NormalizedRect, val value: Double?, val isBest: Boolean,
    val breakdown: com.firstpick.advisor.ValueBreakdown? = null,
    val model: com.firstpick.ui.ModelExplain? = null,
)

@Composable
internal fun DraftScoreBadges(marks: List<DraftScoreMark>, width: Dp, height: Dp) {
    Box(androidx.compose.ui.Modifier.size(width, height)) {
        for (mark in marks) {
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

    if (m.isBest) {
        val haloColor = if (isBombTier(m.value)) Color(0xFFFFC02E) else Color(0xFF7FD1C4)
        val haloD = badge * 1.18f
        val haloCenterY = top + badge * 0.49f
        Box(
            Modifier
                .offset((cx - haloD / 2f).dp, (haloCenterY - haloD / 2f).dp)
                .size(haloD.dp)
                .background(Brush.radialGradient(listOf(haloColor.copy(alpha = 0.38f), Color.Transparent))),
        )
    }

    if (m.breakdown == null) Box(Modifier.offset((cx - badge / 2f).dp, top.dp)) {
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
                modifier = Modifier.align(Alignment.Center).offset(y = (-0.015f * badge).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    letterGrade(value),
                    color = accent,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (0.245f * badge).sp,
                    lineHeight = (0.23f * badge).sp,
                    maxLines = 1,
                )
                Text(
                    value.roundToInt().toString(),
                    color = Color(0xFFF2E9D8),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = (0.145f * badge).sp,
                    lineHeight = (0.145f * badge).sp,
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
    val keyline = Color(0xFF070B0A)
    val face = Color(0xFF0E1312)
    val rearFace = lerp(accent, face, 0.42f)

    fun card(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        degrees: Float,
        fill: Color,
    ) {
        val pivot = Offset(left + width / 2f, top + height / 2f)
        rotate(degrees = degrees, pivot = pivot) {
            drawRoundRect(
                color = keyline,
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = CornerRadius(width * 0.14f),
            )
            val inset = width * 0.055f
            drawRoundRect(
                color = fill,
                topLeft = Offset(left + inset, top + inset),
                size = Size(width - 2f * inset, height - 2f * inset),
                cornerRadius = CornerRadius(width * 0.10f),
            )
        }
    }

    card(
        left = size.width * 0.13f,
        top = size.height * 0.19f,
        width = size.width * 0.43f,
        height = size.height * 0.68f,
        degrees = -10f,
        fill = rearFace,
    )
    card(
        left = size.width * 0.44f,
        top = size.height * 0.19f,
        width = size.width * 0.43f,
        height = size.height * 0.68f,
        degrees = 10f,
        fill = rearFace,
    )

    val frontLeft = size.width * 0.25f
    val frontTop = size.height * 0.06f
    val frontWidth = size.width * 0.50f
    val frontHeight = size.height * 0.81f
    drawRoundRect(
        color = keyline,
        topLeft = Offset(frontLeft, frontTop),
        size = Size(frontWidth, frontHeight),
        cornerRadius = CornerRadius(frontWidth * 0.14f),
    )

    val rimInset = frontWidth * 0.055f
    drawRoundRect(
        color = accent,
        topLeft = Offset(frontLeft + rimInset, frontTop + rimInset),
        size = Size(frontWidth - 2f * rimInset, frontHeight - 2f * rimInset),
        cornerRadius = CornerRadius(frontWidth * 0.10f),
    )

    val faceInset = frontWidth * 0.105f
    drawRoundRect(
        color = face,
        topLeft = Offset(frontLeft + faceInset, frontTop + faceInset),
        size = Size(frontWidth - 2f * faceInset, frontHeight - 2f * faceInset),
        cornerRadius = CornerRadius(frontWidth * 0.065f),
    )
}
