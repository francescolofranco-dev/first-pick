package com.firstpick.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

/** Distinctive (ASCII) NSWindow title so [MacOverlay] can find the drafting panel. */
private const val DRAFTING_OVERLAY_TITLE = "FirstPick Picks"

/** The always-on-top recommendations panel, positioned over the live Arena window. */
@Composable
fun DraftingOverlay(state: DraftUiState) {
    if (state.packCards.isEmpty()) return

    var mtgaBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
    // Whether to let clicks fall through to Arena (user setting, persisted).
    val clickThrough = remember { OverlaySettings.load().clickThrough }

    // Poll the MTGA window bounds on macOS so the panel tracks Arena as it moves.
    LaunchedEffect(Unit) {
        var lastBounds: java.awt.Rectangle? = null
        while (true) {
            val bounds = getMTGAWindowBounds()
            if (bounds != null && bounds != lastBounds) {
                mtgaBounds = bounds
                lastBounds = bounds
            }
            delay(500)
        }
    }

    val bounds = mtgaBounds ?: return

    // Check if window has a titlebar to apply offset
    val ratioWithTitleBar = bounds.width.toFloat() / (bounds.height - 28f)
    val hasTitleBar = kotlin.math.abs(ratioWithTitleBar - 1.777f) < 0.05f ||
        kotlin.math.abs(ratioWithTitleBar - 1.6f) < 0.05f ||
        kotlin.math.abs(ratioWithTitleBar - 1.54f) < 0.05f ||
        kotlin.math.abs(ratioWithTitleBar - 1.33f) < 0.05f

    val clientYOffset = if (hasTitleBar) 28 else 0

    // Sleek single list panel positioned top-right of MTGA client
    val panelWidth = 260.dp
    val panelHeight = 420.dp

    val windowX = bounds.x + bounds.width - 270 // 260 width + 10 margin
    val windowY = bounds.y + clientYOffset + 40 // offset below top elements

    val wState = rememberWindowState(
        position = WindowPosition(windowX.dp, windowY.dp),
        size = DpSize(panelWidth, panelHeight)
    )

    // Ensure position updates if bounds change
    LaunchedEffect(windowX, windowY) {
        wState.position = WindowPosition(windowX.dp, windowY.dp)
    }

    androidx.compose.ui.window.Window(
        onCloseRequest = {},
        state = wState,
        title = DRAFTING_OVERLAY_TITLE,
        transparent = true,
        undecorated = true,
        alwaysOnTop = true,
        focusable = false
    ) {
        // Let clicks fall through to Arena beneath the panel. The NSWindow may not
        // be registered the instant the window opens, so retry briefly. macOS-only,
        // best-effort (see [MacOverlay]); the panel is read-only so nothing is lost.
        LaunchedEffect(clickThrough) {
            repeat(15) {
                if (MacOverlay.setClickThrough(DRAFTING_OVERLAY_TITLE, clickThrough)) return@LaunchedEffect
                delay(200)
            }
        }
        MaterialTheme(colorScheme = DraftColorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFA121212), // Sleek, almost completely opaque dark background
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Header / Lane Indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "FirstPick recommendations",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.weight(1f)
                        )
                        if (state.laneColors.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                state.laneColors.forEach { color ->
                                    Pip(color, size = 16.dp)
                                }
                            }
                        }
                    }

                    Divider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    // List of cards
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Show top 10 recommended cards
                        items(state.packCards.take(10)) { card ->
                            OverlayListItem(card)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayListItem(card: PackCardUi) {
    val isBomb = card.isBomb
    val tierColor = valueTierColor(card.value)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isBomb) tierColor.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
            .border(if (isBomb) 1.dp else 0.dp, if (isBomb) tierColor.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Color-coded letter grade + 0–100 value, matching the main window.
        if (card.breakdown != null) {
            TooltipArea(tooltip = { BreakdownTooltip(card.breakdown) }) { GradeBadge(card.value, size = 34.dp) }
        } else {
            GradeBadge(card.value, size = 34.dp)
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.isBomb) Text("★ ", color = Color(0xFFFFD700), fontSize = 12.sp)
                Text(
                    text = card.name,
                    color = Color.White.copy(alpha = if (isBomb) 1.0f else 0.92f),
                    fontWeight = if (isBomb) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (card.color.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        card.color.forEach { color -> Pip(color, size = 13.dp) }
                    }
                }
            }
            if (card.reasons.isNotEmpty()) {
                Text(
                    text = card.reasons.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
