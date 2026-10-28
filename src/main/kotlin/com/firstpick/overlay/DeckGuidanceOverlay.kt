package com.firstpick.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
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
        val compact: Boolean = false,
    ) : DeckGuidanceVisualMark

    data class AddCard(
        override val bounds: NormalizedRect,
        val inDeckList: Boolean = false,
        val compact: Boolean = false,
    ) : DeckGuidanceVisualMark
}

data class DeckGuidanceOverlayModel(
    val status: DeckGuidanceStatus,
    val marks: List<DeckGuidanceVisualMark> = emptyList(),
    val remainingAdds: Int = 0,
    val remainingRemovals: Int = 0,
    val basicLandDelta: Int = 0,
    val changes: List<CardGuidance> = emptyList(),
    val notice: String? = null,
    val basicLandChanges: List<CardGuidance> = emptyList(),
) {
    /** Partial readings only contain marks from the current verified visible cards. */
    fun renderableMarks(): List<DeckGuidanceVisualMark> =
        if (status == DeckGuidanceStatus.NEEDS_CHANGES || status == DeckGuidanceStatus.READING) marks.filter { mark ->
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
                is DeckGuidanceVisualMark.RemoveRow -> ActionBadge(rect, adding = false, inDeckList = true, compact = mark.compact)
                is DeckGuidanceVisualMark.AddCard -> ActionBadge(rect, adding = true, inDeckList = mark.inDeckList, compact = mark.compact)
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopCenter)
                .offset(y = viewportHeight * STATUS_TOP_FRACTION)
                .widthIn(max = viewportWidth * 0.74f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusPill(style = statusPillStyle(model))
            val detail = if (model.status == DeckGuidanceStatus.NEEDS_CHANGES)
                model.basicLandChanges.filter { it.action != DeckGuidanceAction.OK }.joinToString(" · ") { it.basicLandInstruction() }
                else ""
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    modifier = Modifier.padding(top = 4.dp)
                        .background(DeckGuidanceOverlayPalette.PillBackground, BADGE_SHAPE)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = DeckGuidanceOverlayPalette.PillText,
                    fontSize = 12.sp,
                    maxLines = 8,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
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

/** Compact labels use a row's center or a pool card's top, never trace Arena's chrome. */
@Composable
private fun ActionBadge(rect: GuidanceViewportRect, adding: Boolean, inDeckList: Boolean, compact: Boolean) {
    if (compact && rect.height < 12f) return
    val height = if (compact) 8f else if (inDeckList) rect.height.coerceIn(14f, 22f) else 16f
    val width = if (compact) 24f else if (inDeckList) 54f else 36f
    val left = if (compact) rect.left + 3f else if (inDeckList) rect.left - width - 6f else rect.left
    val top = if (compact) rect.top + rect.height - height - 1f
        else if (inDeckList) rect.top + (rect.height - height) / 2f else rect.top - height - 4f
    val accent = if (adding) DeckGuidanceOverlayPalette.AddAccent else DeckGuidanceOverlayPalette.RemoveAccent
    Box(
        Modifier
            .offset(left.coerceAtLeast(0f).dp, top.coerceAtLeast(0f).dp)
            .size(width.dp, height.dp)
            .background(DeckGuidanceOverlayPalette.PillBackground, BADGE_SHAPE)
            .border(1.dp, accent, BADGE_SHAPE),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (compact) { if (adding) "Add" else "Cut" } else if (adding) "+ Add" else "− Cut",
            color = accent,
            fontSize = (if (compact) 6.5f else height * 0.55f).sp,
            lineHeight = (if (compact) 7f else height * 0.65f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
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

fun statusPillStyle(model: DeckGuidanceOverlayModel): DeckGuidanceStatusPillStyle = model.notice?.let {
    DeckGuidanceStatusPillStyle(it, DeckGuidanceOverlayPalette.ReadingAccent)
} ?: when (model.status) {
    DeckGuidanceStatus.READING -> DeckGuidanceStatusPillStyle("Checking recent changes…", DeckGuidanceOverlayPalette.ReadingAccent)
    DeckGuidanceStatus.NEEDS_CHANGES -> DeckGuidanceStatusPillStyle(model.needsChangesLabel(), DeckGuidanceOverlayPalette.RemoveAccent)
    DeckGuidanceStatus.MATCHES -> DeckGuidanceStatusPillStyle("Deck matches build", DeckGuidanceOverlayPalette.AddAccent)
    DeckGuidanceStatus.UNABLE -> DeckGuidanceStatusPillStyle("Unable to read deck", DeckGuidanceOverlayPalette.UnableAccent)
}

internal fun DeckGuidanceOverlayModel.guidanceDetails(): String {
    if (status != DeckGuidanceStatus.NEEDS_CHANGES) return ""
    val cards = changes.map {
        "${if (it.action == DeckGuidanceAction.ADD) "Add" else "Cut"} ${kotlin.math.abs(it.difference)} ${it.name}"
    }
    return (cards + basicLandChanges.filter { it.action != DeckGuidanceAction.OK }.map { it.basicLandInstruction() })
        .joinToString(" · ")
}

private fun CardGuidance.basicLandInstruction(): String =
    "${if (action == DeckGuidanceAction.ADD) "Add" else "Remove"} ${kotlin.math.abs(difference)} $name"

private fun DeckGuidanceOverlayModel.needsChangesLabel(): String {
    val namedChanges = buildList {
        if (remainingRemovals > 0) add("Cut $remainingRemovals ${if (remainingRemovals == 1) "card" else "cards"}")
        if (remainingAdds > 0) add("Add $remainingAdds ${if (remainingAdds == 1) "card" else "cards"}")
    }
    val basicInstructions = basicLandChanges.filter { it.action != DeckGuidanceAction.OK }.map { it.basicLandInstruction() }
    if (basicInstructions.isNotEmpty()) {
        val instructions = (namedChanges + basicInstructions).joinToString(" · ")
        return if (namedChanges.isNotEmpty()) "Deck needs changes · $instructions" else instructions
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
    val RemoveAccent = Color(0xFFF2555A)

    val AddAccent = Color(0xFF4CC38A)

    val ReadingAccent = Color(0xFFF5B638)
    val UnableAccent = Color(0xFFA0A5B1)
    val PillBackground = Color(0xF51A1C22)
    val PillText = Color(0xFFF3F4F6)
}

private val BADGE_SHAPE = RoundedCornerShape(5.dp)
private val PILL_SHAPE = RoundedCornerShape(999.dp)
private const val STATUS_TOP_FRACTION = 0.115f
private val PILL_OUTLINE_WIDTH = 1.dp
private const val MAX_RENDERED_COUNT = 99
