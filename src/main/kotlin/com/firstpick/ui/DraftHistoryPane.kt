package com.firstpick.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.model.DraftState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun DraftHistoryPane(
    history: List<DraftHistoryEntry>,
    onReviewDraft: suspend (DraftState) -> DraftUiState,
) {
    var selectedSet by remember { mutableStateOf("All sets") }
    var setMenuOpen by remember { mutableStateOf(false) }
    val visibleHistory = history.filter { selectedSet == "All sets" || it.state.setCode == selectedSet }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = history.firstOrNull { it.id == selectedId }
    var showPool by remember(selectedId) { mutableStateOf(false) }
    val review by produceState<DraftUiState?>(null, selected) {
        value = null
        if (selected != null) value = withContext(Dispatchers.IO) {
            runCatching { onReviewDraft(selected.state) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    DraftUiState(dataError = "Could not load this draft. Return to history and try again.")
                }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selected == null) {
            Text("Draft history", style = AppTheme.typography.paneTitle, color = AppTheme.colors.textPrimary)
            Text("Completed drafts are saved on this Mac. Select one to review its builds and drafted cards.",
                style = AppTheme.typography.caption, color = AppTheme.colors.textSecondary)
            Box {
                AppButton(onClick = { setMenuOpen = true }) { Text("$selectedSet ▾ · ${visibleHistory.size} drafts") }
                DropdownMenu(setMenuOpen, onDismissRequest = { setMenuOpen = false }) {
                    for (code in listOf("All sets") + history.mapNotNull { it.state.setCode }.distinct().sorted()) {
                        DropdownMenuItem(text = { Text(code) }, onClick = { selectedSet = code; setMenuOpen = false })
                    }
                }
            }
            if (history.isEmpty()) {
                Text("No completed drafts yet. Drafts found in Arena’s current log will appear here too.",
                    color = AppTheme.colors.textSecondary)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleHistory, key = { it.id }) { entry ->
                    AppButton(onClick = { selectedId = entry.id }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(4.dp)) {
                            Text("${entry.state.setCode ?: "Unknown set"} · ${if (entry.state.picksPerChoice == 2) "Pick Two Draft" else entry.state.format.label}",
                                color = AppTheme.colors.textPrimary)
                            val date = runCatching {
                                DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm").withZone(ZoneId.systemDefault())
                                    .format(Instant.parse(entry.completedAt))
                            }.getOrDefault(entry.completedAt)
                            Text("Saved $date · ${entry.state.pool.size} cards", color = AppTheme.colors.textSecondary)
                        }
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton(onClick = { selectedId = null }) { Text("← History") }
                Text("${selected.state.setCode ?: "Draft"} · ${selected.state.pool.size} cards",
                    color = AppTheme.colors.textPrimary, modifier = Modifier.padding(top = 8.dp))
            }
            AppSegmentedTabs(tabs = listOf(
                SegmentedTabItem("Deck builds", selected = !showPool, onClick = { showPool = false }),
                SegmentedTabItem("Drafted cards", selected = showPool, onClick = { showPool = true }),
            ), modifier = Modifier.fillMaxWidth(), fillWidth = true)
            val state = review
            if (state == null) {
                Text("Loading draft…", color = AppTheme.colors.textSecondary)
            } else {
                state.dataError?.let { Text(it, color = AppTheme.colors.warning) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (showPool) {
                        if (state.draftPool.isEmpty()) Text("Card data is unavailable. Return to history and try again when connected.",
                            color = AppTheme.colors.textSecondary)
                        else ScrollableDeckPane { items(state.draftPool) { DeckSpellRow(it) } }
                    } else if (state.deckOptions.isNotEmpty()) {
                        DeckBuilderPane(state.deckOptions, savedDeck = state.savedDeck, committedDeck = null, onUseDeck = {})
                    } else {
                        Text("Deck suggestions are unavailable for this pool. You can still review the drafted cards.",
                            color = AppTheme.colors.textSecondary)
                    }
                }
            }
        }
    }
}
