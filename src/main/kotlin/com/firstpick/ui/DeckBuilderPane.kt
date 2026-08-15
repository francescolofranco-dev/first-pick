package com.firstpick.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val DeckOptionUi.deckBuildKey: String
    get() = colors

internal fun resolveDeckPreviewKey(
    optionKeys: List<String>,
    previewKey: String?,
    committedKey: String?,
): String? = previewKey?.takeIf { it in optionKeys }
    ?: committedKey?.takeIf { it in optionKeys }
    ?: optionKeys.firstOrNull()

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeckBuilderPane(
    options: List<DeckOptionUi>,
    committedDeck: DeckOptionUi?,
    onUseDeck: (DeckOptionUi) -> Unit,
    onStopGuidance: () -> Unit,
) {
    if (options.isEmpty()) return

    val optionKeys = options.map { it.deckBuildKey }
    val committedKey = committedDeck?.deckBuildKey
    var previewKey by remember { mutableStateOf<String?>(null) }
    val resolvedPreviewKey = resolveDeckPreviewKey(optionKeys, previewKey, committedKey)
    val sel = options.first { it.deckBuildKey == resolvedPreviewKey }

    LaunchedEffect(optionKeys, committedKey) {
        previewKey = resolveDeckPreviewKey(optionKeys, previewKey, committedKey)
    }

    val spellCount = sel.spells.sumOf { it.count }
    Column(Modifier.fillMaxSize()) {
        Text("Draft complete — pick your build", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            if (committedDeck == null) {
                "Preview a build, then confirm it to start Arena guidance."
            } else {
                "Guidance active for ${committedDeck.title}. Preview another build and confirm to change it."
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt ->
                OptionCard(
                    opt = opt,
                    previewed = opt.deckBuildKey == resolvedPreviewKey,
                    active = opt.deckBuildKey == committedKey,
                    onClick = { previewKey = opt.deckBuildKey },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            GuidanceAction(
                label = when {
                    committedKey == null -> "Use this build"
                    committedKey == resolvedPreviewKey -> "Guidance active"
                    else -> "Use this build"
                },
                enabled = committedKey != resolvedPreviewKey,
                onClick = { onUseDeck(sel) },
            )
            if (committedDeck != null) {
                GuidanceAction(
                    label = "Stop guidance",
                    emphasized = false,
                    onClick = onStopGuidance,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${sel.title} (${sel.colors}) · $spellCount spells · ${sel.landLine}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            (listOf(sel.identityLine) + sel.identityReasons.take(2) + sel.powerReasons.take(2)).joinToString(" · "),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
internal fun DeckSoFarPane(deck: DeckOptionUi, cuts: List<DeckSpellUi>) {
    val spellCount = deck.spells.sumOf { it.count }
    Column(Modifier.fillMaxSize()) {
        Text("Deck so far", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(2.dp))
        Text(
            "Projected from your picks — updates every pick, not a final build.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(deck.colors.toList())
            Spacer(Modifier.width(6.dp))
            Text(deck.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            TierBadge(deck.tier)
            Spacer(Modifier.width(8.dp))
            Text("Power ${deck.power}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "$spellCount/23 spells · ${deck.creatures} creatures · ${deck.removal} removal",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            (listOf(deck.identityLine) + deck.powerReasons.take(2)).joinToString(" · "),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        DeckListHeader()
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item { SectionLabel("In the deck") }
            items(deck.spells) { DeckSpellRow(it) }
            if (deck.lands.isNotEmpty()) {
                item { SectionLabel("Lands") }
                items(deck.lands) { DeckSpellRow(it) }
            }
            if (cuts.isNotEmpty()) {
                item { SectionLabel("Not making the cut yet") }
                items(cuts) { DeckSpellRow(it, muted = true) }
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
private fun DeckSpellRow(s: DeckSpellUi, muted: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().alpha(if (muted) 0.5f else 1f).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (s.isLand) "–" else "${s.cmc}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
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
            CardPreview(s.imageUrl, description = s.name) {
                Text(s.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
private fun OptionCard(opt: DeckOptionUi, previewed: Boolean, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (previewed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .semantics {
                selected = previewed
                stateDescription = buildString {
                    append(if (previewed) "Previewed" else "Not previewed")
                    if (active) append(", guidance active")
                }
            }
            .focusable()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (active) {
            Text(
                "GUIDANCE ACTIVE",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PipRow(opt.colors.toList())
            Spacer(Modifier.width(6.dp))
            Text(opt.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierBadge(opt.tier)
            Spacer(Modifier.width(8.dp))
            Text("Power ${opt.power}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Text(opt.identityLine, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(opt.outlook, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${opt.creatures} creatures · ${opt.removal} removal", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GuidanceAction(
    label: String,
    enabled: Boolean = true,
    emphasized: Boolean = true,
    onClick: () -> Unit,
) {
    if (emphasized) {
        Button(onClick = onClick, enabled = enabled) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
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
