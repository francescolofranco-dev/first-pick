package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface DeckGuidanceVisualMark {
    val bounds: NormalizedRect

    data class RemoveRow(
        override val bounds: NormalizedRect,
        val currentCount: Int,
    ) : DeckGuidanceVisualMark

    data class AddCard(
        override val bounds: NormalizedRect,
    ) : DeckGuidanceVisualMark
}

data class DeckGuidanceOverlayModel(
    val status: DeckGuidanceStatus,
    val marks: List<DeckGuidanceVisualMark> = emptyList(),
    val remainingAdds: Int = 0,
    val remainingRemovals: Int = 0,
    val basicLandDelta: Int = 0,
) {
    /** Reading, success, and failure states never retain actionable marks. */
    fun renderableMarks(): List<DeckGuidanceVisualMark> =
        if (status == DeckGuidanceStatus.NEEDS_CHANGES) marks.filter { mark ->
            mark.bounds.isValid() && (mark !is DeckGuidanceVisualMark.RemoveRow || mark.currentCount in 1..MAX_RENDERED_COUNT)
        } else {
            emptyList()
        }
}

/**
 * Converts domain guidance to a renderable mark. Correct cards intentionally
 * produce no mark; invalid geometry and impossible counts also fail closed.
 */
fun DeckGuidanceAction.toVisualMark(
    bounds: NormalizedRect,
    currentCount: Int = 0,
): DeckGuidanceVisualMark? {
    if (!bounds.isValid()) return null
    return when (this) {
        DeckGuidanceAction.ADD -> DeckGuidanceVisualMark.AddCard(bounds)
        DeckGuidanceAction.REMOVE -> currentCount.takeIf { it in 1..MAX_RENDERED_COUNT }
            ?.let { DeckGuidanceVisualMark.RemoveRow(bounds, it) }
        DeckGuidanceAction.OK -> null
    }
}

/** A unit-agnostic rectangle after normalized coordinates are scaled to a viewport. */
data class GuidanceViewportRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

fun NormalizedRect.inViewport(
    viewportWidth: Float,
    viewportHeight: Float,
): GuidanceViewportRect? {
    if (!isValid() || !viewportWidth.isFinite() || !viewportHeight.isFinite()) return null
    if (viewportWidth <= 0f || viewportHeight <= 0f) return null
    return GuidanceViewportRect(
        left = (x * viewportWidth).toFloat(),
        top = (y * viewportHeight).toFloat(),
        width = (width * viewportWidth).toFloat(),
        height = (height * viewportHeight).toFloat(),
    )
}

/**
 * Window-agnostic overlay content. The caller owns the transparent Window,
 * tracking, click-through, and lifecycle.
 */
@Composable
fun DeckGuidanceOverlay(
    model: DeckGuidanceOverlayModel,
    viewportWidth: Dp,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(viewportWidth, viewportHeight)) {
        for (mark in model.renderableMarks()) {
            val rect = mark.bounds.inViewport(viewportWidth.value, viewportHeight.value) ?: continue
            when (mark) {
                is DeckGuidanceVisualMark.RemoveRow -> RemoveRowMark(rect)
                is DeckGuidanceVisualMark.AddCard -> AddCardMark(rect)
            }
        }
        StatusPill(
            style = statusPillStyle(model),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = viewportHeight * STATUS_TOP_FRACTION),
        )
    }
}

/** Convenience overload for a viewport measured in physical pixels. */
@Composable
fun DeckGuidanceOverlayPixels(
    model: DeckGuidanceOverlayModel,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    modifier: Modifier = Modifier,
) {
    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return
    with(LocalDensity.current) {
        DeckGuidanceOverlay(
            model = model,
            viewportWidth = viewportWidthPx.toDp(),
            viewportHeight = viewportHeightPx.toDp(),
            modifier = modifier,
        )
    }
}

@Composable
private fun RemoveRowMark(rect: GuidanceViewportRect) {
    Box(
        Modifier
            .offset(rect.left.dp, rect.top.dp)
            .size(rect.width.dp, rect.height.dp)
            .border(ROW_OUTLINE_WIDTH, DeckGuidanceOverlayPalette.RemoveOutline, ROW_SHAPE),
    )
}

@Composable
private fun AddCardMark(rect: GuidanceViewportRect) {
    Box(
        Modifier
            .offset(rect.left.dp, rect.top.dp)
            .size(rect.width.dp, rect.height.dp)
            .clip(CARD_SHAPE)
            .background(DeckGuidanceOverlayPalette.AddTint)
            .border(CARD_OUTLINE_WIDTH, DeckGuidanceOverlayPalette.AddOutline, CARD_SHAPE),
    )
}

@Composable
private fun StatusPill(
    style: DeckGuidanceStatusPillStyle,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(PILL_SHAPE)
            .background(DeckGuidanceOverlayPalette.PillBackground)
            .border(PILL_OUTLINE_WIDTH, style.accent.copy(alpha = 0.82f), PILL_SHAPE)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(style.accent, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(
                text = style.label,
                color = DeckGuidanceOverlayPalette.PillText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

data class DeckGuidanceStatusPillStyle(
    val label: String,
    val accent: Color,
)

fun statusPillStyle(status: DeckGuidanceStatus): DeckGuidanceStatusPillStyle =
    statusPillStyle(DeckGuidanceOverlayModel(status))

fun statusPillStyle(model: DeckGuidanceOverlayModel): DeckGuidanceStatusPillStyle = when (model.status) {
    DeckGuidanceStatus.READING -> DeckGuidanceStatusPillStyle("Reading deck…", DeckGuidanceOverlayPalette.ReadingAccent)
    DeckGuidanceStatus.NEEDS_CHANGES -> DeckGuidanceStatusPillStyle(model.needsChangesLabel(), DeckGuidanceOverlayPalette.RemoveAccent)
    DeckGuidanceStatus.MATCHES -> DeckGuidanceStatusPillStyle("Deck matches build", DeckGuidanceOverlayPalette.AddAccent)
    DeckGuidanceStatus.UNABLE -> DeckGuidanceStatusPillStyle("Unable to read deck", DeckGuidanceOverlayPalette.UnableAccent)
}

private fun DeckGuidanceOverlayModel.needsChangesLabel(): String {
    val namedChanges = buildList {
        if (remainingRemovals > 0) add("Cut $remainingRemovals ${if (remainingRemovals == 1) "card" else "cards"}")
        if (remainingAdds > 0) add("Add $remainingAdds ${if (remainingAdds == 1) "card" else "cards"}")
    }
    if (namedChanges.isNotEmpty()) {
        val completeInstructions = namedChanges + when {
            basicLandDelta < 0 -> listOf("Remove ${-basicLandDelta} basic ${if (basicLandDelta == -1) "land" else "lands"}")
            basicLandDelta > 0 -> listOf("Add $basicLandDelta basic ${if (basicLandDelta == 1) "land" else "lands"}")
            else -> emptyList()
        }
        return "Deck needs changes · ${completeInstructions.joinToString(" · ")}"
    }
    return when {
        basicLandDelta < 0 -> "Remove ${-basicLandDelta} basic ${if (basicLandDelta == -1) "land" else "lands"}"
        basicLandDelta > 0 -> "Add $basicLandDelta basic ${if (basicLandDelta == 1) "land" else "lands"}"
        else -> "Deck needs changes"
    }
}

object DeckGuidanceOverlayPalette {
    val RemoveAccent = Color(0xFFFF4D5E)
    val RemoveOutline = Color(0xFFFF4D5E)

    val AddAccent = Color(0xFF50D890)
    val AddOutline = Color(0xE650D890)
    val AddTint = Color(0x2450D890)

    val ReadingAccent = Color(0xFFFFC857)
    val UnableAccent = Color(0xFFA8AFB8)
    val PillBackground = Color(0xF2181718)
    val PillText = Color(0xFFF7F1E8)
}

private val ROW_SHAPE = RoundedCornerShape(7.dp)
private val CARD_SHAPE = RoundedCornerShape(9.dp)
private val PILL_SHAPE = RoundedCornerShape(999.dp)
private const val STATUS_TOP_FRACTION = 0.115f
private val ROW_OUTLINE_WIDTH = 2.dp
private val CARD_OUTLINE_WIDTH = 2.dp
private val PILL_OUTLINE_WIDTH = 1.dp
private const val MAX_RENDERED_COUNT = 99
