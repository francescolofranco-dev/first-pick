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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun DeckBuilderPane(options: List<DeckOptionUi>) {
    var selected by remember(options) { mutableStateOf(0) }
    val sel = options.getOrElse(selected) { options.first() }
    Column(Modifier.fillMaxSize()) {
        Text("Draft complete — pick your build", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, opt -> OptionCard(opt, i == selected) { selected = i } }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "${sel.title} (${sel.pair}) · ${sel.spells.size} spells · lands ${sel.landLine}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // No key: a deck can hold duplicate cards (multiple copies of a common).
            items(sel.spells) { DeckSpellRow(it) }
        }
    }
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
            PipRow(opt.pair.toList())
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

@Composable
private fun DeckSpellRow(s: DeckSpellUi) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${s.cmc}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
        CardPreview(s.imageUrl, modifier = Modifier.weight(1f)) {
            Text(s.name, fontSize = 12.sp)
        }
        Text(s.color.ifBlank { "—" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(48.dp))
        Text(s.gihWr.asPct(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(48.dp))
    }
}
