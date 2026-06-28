package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowState

/** Post-draft companion overlay: pick a build and tick off cards as you add them. */
@Composable
fun DeckBuilderOverlay(
    composeWindow: java.awt.Window,
    state: DraftUiState,
    windowState: WindowState,
    onClose: () -> Unit
) {
    var isMinimized by remember { mutableStateOf(false) }
    var selectedOptionIndex by remember(state.deckOptions) { mutableStateOf<Int?>(null) }

    // Auto-resize / adjust layout when switching modes or minimized
    LaunchedEffect(isMinimized, selectedOptionIndex) {
        if (isMinimized) {
            windowState.size = DpSize(150.dp, 40.dp)
        } else {
            // Deck building checklist mode
            if (selectedOptionIndex != null) {
                windowState.size = DpSize(920.dp, 280.dp)
            } else {
                windowState.size = DpSize(620.dp, 190.dp)
            }
        }
    }

    MaterialTheme(colorScheme = DraftColorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x3B7FD1C4), RoundedCornerShape(12.dp)),
            color = Color(0xF20F1413),
            // The background is a custom color (not a scheme role), so set the content color
            // explicitly — otherwise unstyled text defaults to black and is invisible here.
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            if (isMinimized) {
                // Minimized floating pill
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowDraggable(composeWindow),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "FP Overlay",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OverlayIconButton(
                            text = "+",
                            onClick = { isMinimized = false },
                            tooltip = "Expand overlay"
                        )
                        OverlayIconButton(
                            text = "✕",
                            onClick = onClose,
                            tooltip = "Close overlay"
                        )
                    }
                }
            } else {
                // Deck builder mode (Companion Window UI)
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .fillMaxHeight()
                            .background(Color(0x1A7FD1C4))
                            .windowDraggable(composeWindow),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            repeat(3) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape))
                                    Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape))
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Draft complete · deck builder companion",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OverlayIconButton(
                                    text = "−",
                                    onClick = { isMinimized = true },
                                    tooltip = "Minimize overlay"
                                )
                                OverlayIconButton(
                                    text = "✕",
                                    onClick = onClose,
                                    tooltip = "Close overlay"
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            DeckHelperView(
                                options = state.deckOptions,
                                selectedIdx = selectedOptionIndex,
                                onSelectIdx = { selectedOptionIndex = it }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckHelperView(
    options: List<DeckOptionUi>,
    selectedIdx: Int?,
    onSelectIdx: (Int?) -> Unit
) {
    if (options.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Building deck options...", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (selectedIdx == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Select a deck build to follow:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                options.forEachIndexed { idx, opt ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .clickable { onSelectIdx(idx) }
                            .padding(8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PipRow(opt.pair.toList())
                                Spacer(Modifier.width(6.dp))
                                Text(opt.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.tertiary)
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(opt.tier, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text("Power: ${opt.power}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                            Text(opt.type, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(opt.outlook, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    } else {
        val sel = options.getOrElse(selectedIdx) { options.first() }
        val checkedCounts = remember(selectedIdx) { mutableStateMapOf<String, Int>() }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PipRow(sel.pair.toList())
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${sel.title} build checklist",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${sel.spells.size} spells · lands ${sel.landLine}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Change build",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .clickable { onSelectIdx(null) }
                        .padding(horizontal = 4.dp)
                )
            }

            val groupedSpells = remember(sel.spells) {
                val groups = sel.spells.groupBy { spell ->
                    val c = spell.color.trim()
                    if (c.length == 1) c.first() else 'M'
                }
                listOf('W', 'U', 'B', 'R', 'G', 'M')
                    .map { it to (groups[it] ?: emptyList()) }
                    .filter { it.second.isNotEmpty() }
            }

            LazyRow(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groupedSpells) { (colorChar, spells) ->
                    val colorName = when (colorChar) {
                        'W' -> "White"
                        'U' -> "Blue"
                        'B' -> "Black"
                        'R' -> "Red"
                        'G' -> "Green"
                        else -> "Multi/Other"
                    }
                    val badgeColor = pipColor(colorChar)

                    Column(
                        modifier = Modifier
                            .width(170.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x0F7FD1C4))
                            .border(1.dp, Color(0x08FFFFFF), RoundedCornerShape(6.dp))
                            .padding(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Box(Modifier.size(8.dp).background(badgeColor, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "$colorName (${spells.size})",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Within each color column, order by mana value (then win rate) so the
                        // overlay matches the deck pane's MV + WUBRG ordering.
                        val sortedSpells = remember(spells) {
                            spells.sortedWith(compareBy({ it.cmc }, { -(it.gihWr ?: 0.0) }))
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(sortedSpells) { spell ->
                                val name = spell.name
                                val totalCount = spell.count
                                val imageUrl = spell.imageUrl
                                val checkedCount = checkedCounts[name] ?: 0
                                val isComplete = checkedCount >= totalCount

                                CardPreview(imageUrl) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (isComplete) Color(0x1A5FD08C)
                                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                            )
                                            .clickable {
                                                val next = if (checkedCount >= totalCount) 0 else checkedCount + 1
                                                checkedCounts[name] = next
                                            }
                                            .padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isComplete) "✓ " else "$checkedCount/$totalCount ",
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isComplete) Color(0xFF5FD08C) else MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = name,
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textDecoration = if (isComplete) TextDecoration.LineThrough else null,
                                            color = if (isComplete) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayIconButton(
    text: String,
    onClick: () -> Unit,
    tooltip: String
) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Trim the line's leading and center the glyph so the symbol sits dead-center
            // in the circle (default leading made "－"/"✕" look top/bottom-heavy).
            style = LocalTextStyle.current.copy(
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
