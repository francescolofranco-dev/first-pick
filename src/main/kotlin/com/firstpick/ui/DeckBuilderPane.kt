package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DeckBuilderPane(options: List<DeckOptionUi>) {
    var selected by remember(options) { mutableStateOf(0) }
    val sel = options.getOrElse(selected) { options.first() }
    val spellCount = sel.spells.sumOf { it.count }
    Column(Modifier.fillMaxSize()) {
        Text("Draft complete — pick your build", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, opt -> OptionCard(opt, i == selected) { selected = i } }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${sel.title} (${sel.colors}) · $spellCount spells · ${sel.landLine}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        DeckListHeader()
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(sel.spells) { DeckSpellRow(it) }
            if (sel.lands.isNotEmpty()) {
                item { SectionLabel("Lands") }
                items(sel.lands) { DeckSpellRow(it) }
            }
        }
    }
}

@Composable
private fun DeckListHeader() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("MV", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        Text("Card", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Type / role", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
        Text("GIH%", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 8.dp),
    )
}

@Composable
private fun DeckSpellRow(s: DeckSpellUi) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mana value (lands have none).
        Text(
            if (s.isLand) "–" else "${s.cmc}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        // Card name, with a copy count when there's more than one.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (s.count > 1) {
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Text("${s.count}×", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(6.dp))
            }
            CardPreview(s.imageUrl) {
                Text(s.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // Type + role tag.
        Row(Modifier.width(132.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(s.typeLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            s.role?.let { RoleChip(it) }
        }
        Text(
            s.gihWr.asPct(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

@Composable
private fun RoleChip(role: String) {
    val color = roleColor(role)
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(role, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun roleColor(role: String): Color = when (role) {
    "Removal" -> MaterialTheme.colorScheme.error
    "Fixing" -> MaterialTheme.colorScheme.primary
    "Finisher" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.secondary // Draw / Evasion
}

@Composable
private fun OptionCard(opt: DeckOptionUi, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(opt.colors.toList())
            Spacer(Modifier.width(6.dp))
            Text(opt.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(opt.tier)
            Spacer(Modifier.width(8.dp))
            Text("Power ${opt.power}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Text(opt.type, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(opt.outlook, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${opt.creatures} creatures · ${opt.removal} removal", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TierBadge(tier: String) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.tertiary).padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(tier, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}
