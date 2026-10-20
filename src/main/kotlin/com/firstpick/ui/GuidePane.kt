package com.firstpick.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.guide.GuideCard
import com.firstpick.guide.ThemeReference

@Composable
internal fun GuidePane(state: DraftUiState, onLoadSet: (String) -> Unit = {}) {
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.setReferences, key = { it.code }) { set ->
            val open = set.code in expanded
            Surface(shape = MaterialTheme.shapes.medium, color = AppTheme.colors.surfaceElevated) {
                Row(
                    Modifier.fillMaxWidth()
                        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                        .clickable(role = Role.Button) {
                            expanded = if (open) expanded - set.code else expanded + set.code
                            if (!open) onLoadSet(set.code)
                        }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(set.name, style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = AppTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(set.code, fontSize = 11.sp, color = AppTheme.colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text(if (open) "▾" else "▸")
                }
            }
            if (open) {
                when {
                    set.loadingCards -> Text("Loading cards…", fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                    set.cardError -> TextButton(onClick = { onLoadSet(set.code) }) { Text("Retry loading cards") }
                }
                set.themes.forEach { theme -> ThemeRow(theme, set.cardsLoaded) }
                if (set.themes.isEmpty()) Text("Themes unavailable", modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun ThemeRow(theme: ThemeReference, cardsLoaded: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(theme.pair.toList())
            Spacer(Modifier.width(8.dp))
            Text(theme.theme, style = AppTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = AppTheme.colors.textPrimary)
        }
        if (cardsLoaded) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReferenceCards("Commons", theme.commons, Modifier.weight(1f))
                ReferenceCards("Uncommons", theme.uncommons, Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = AppTheme.colors.borderSubtle)
    }
}

@Composable
private fun ReferenceCards(label: String, cards: List<GuideCard>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 10.sp, color = AppTheme.colors.textSecondary)
        cards.forEach { card ->
            CardPreview(card.imageUrl, description = card.name) {
                Text(card.name, style = AppTheme.typography.body, color = AppTheme.colors.textPrimary)
            }
        }
        if (cards.isEmpty()) Text("—", fontSize = 12.sp, color = AppTheme.colors.textSecondary)
    }
}
