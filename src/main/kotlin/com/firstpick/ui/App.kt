package com.firstpick.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firstpick.model.DraftPhase

@Composable
fun App(state: DraftUiState) {
    MaterialTheme(colorScheme = DraftColorScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Header(state)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.deckOptions.isNotEmpty()) DeckBuilderPane(state.deckOptions) else PackPane(state)
                    }
                    if (state.poolSize > 0 || state.packCards.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        Sidebar(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: DraftUiState) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FirstPick", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            if (state.laneColors.isNotEmpty()) PipRow(state.laneColors)
        }
        Spacer(Modifier.height(2.dp))
        Text(state.headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        state.dataError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PackPane(state: DraftUiState) = when {
    state.loadingRatings && state.packCards.isEmpty() -> Centered {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Loading 17Lands data…", style = MaterialTheme.typography.bodyMedium)
        }
    }

    state.packCards.isEmpty() -> Centered {
        Text(
            if (state.phase == DraftPhase.IDLE) {
                "Start a draft in MTG Arena.\nEnable Options → Account → Detailed Logs (Plugin Support)."
            } else {
                "No cards in the current pack."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    else -> PackTable(state.packCards)
}

@Composable
private fun PackTable(cards: List<PackCardUi>) {
    Column(Modifier.fillMaxSize()) {
        ConfidenceBanner(cards)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)) {
            HeaderCell("#", 22.dp)
            HeaderCell("VALUE", 54.dp)
            HeaderCell("Card", weight = 1f)
            HeaderCell("Color", 52.dp)
            HeaderCell("GIH%", 52.dp)
            HeaderCell("ALSA", 48.dp)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Composite key: grpId can repeat in cube/special packs; rank keeps it unique.
            items(cards, key = { "${it.grpId}#${it.rank}" }) { PackRow(it) }
        }
    }
}

/** Flags when the top pick isn't clearly ahead — "use your judgment". */
@Composable
private fun ConfidenceBanner(cards: List<PackCardUi>) {
    val top = cards.getOrNull(0)?.value ?: return
    val second = cards.getOrNull(1)?.value ?: return
    val gap = top - second
    if (gap >= 7.0) return // clear pick — no banner
    val tossUp = gap < 3.0
    val contenders = cards.count { (it.value ?: 0.0) >= top - 3.0 }
    val accent = if (tossUp) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(accent).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(if (tossUp) "TOSS-UP" else "LEAN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (tossUp) "Top $contenders are nearly tied — trust your read." else "Only a slight edge to the top pick.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PackRow(card: PackCardUi) {
    val highlight = card.rank == 1
    val tier = valueTierColor(card.value)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (highlight) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(tier)) // quality accent
        Row(
            Modifier.weight(1f).padding(start = 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MonoCell("${card.rank}", 22.dp)
            Column(Modifier.width(54.dp)) {
                Text(card.value.asInt(), fontFamily = FontFamily.Monospace, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = tier)
                Text(valueTierLabel(card.value), fontSize = 9.sp, color = tier)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (card.isBomb) Text("★ ", fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary)
                    CardPreview(card.imageUrl) {
                        Text(
                            card.name,
                            fontSize = 13.sp,
                            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (card.reasons.isNotEmpty()) {
                    Text(card.reasons.joinToString(" · "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            MonoCell(card.color.ifBlank { "—" }, 52.dp)
            MonoCell(card.gihWr.asPct(), 52.dp)
            MonoCell(card.alsa.as1dp(), 48.dp)
        }
    }
}

// ---- Post-draft deck builder --------------------------------------------

@Composable
private fun DeckBuilderPane(options: List<DeckOptionUi>) {
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
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // No key: a deck can hold duplicate cards (multiple copies of a common).
            items(sel.spells) { DeckSpellRow(it) }
        }
    }
}

@Composable
private fun OptionCard(opt: DeckOptionUi, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(156.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
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

// ---- Sidebar -------------------------------------------------------------

@Composable
private fun Sidebar(state: DraftUiState) {
    Column(
        Modifier.width(232.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Panel("Your Lane") {
            if (state.laneColors.isEmpty()) {
                Text("Undecided", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PipRow(state.laneColors)
                    state.lanePair?.let { pair ->
                        val name = guildName(pair)
                        if (name.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        if (state.archetypes.isNotEmpty()) {
            Panel("Archetypes") {
                val max = state.archetypes.maxOf { it.winRate }
                val min = state.archetypes.minOf { it.winRate }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.archetypes.take(6).forEach { a ->
                        val frac = if (max > min) 0.15 + 0.85 * (a.winRate - min) / (max - min) else 1.0
                        val color = if (a.isLane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                a.pair,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (a.isLane) FontWeight.Bold else FontWeight.Normal,
                                color = if (a.isLane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(26.dp),
                            )
                            Bar(frac.toFloat(), color, Modifier.weight(1f))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "%.1f".format(a.winRate * 100),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.width(30.dp),
                            )
                        }
                    }
                }
            }
        }
        Panel("Deck Needs") { NeedsContent(state.deckNeeds, state.poolSize) }
        Panel("Open Lanes") {
            if (state.openLanes.isEmpty()) {
                Text("Reading signals…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val max = state.openLanes.maxOf { it.score }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    state.openLanes.forEach { ColorBar(it.color, it.score / max) }
                }
            }
        }
        Panel("Mana Curve") {
            val max = (state.manaCurve.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                state.manaCurve.forEach { bar ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bar.cmcLabel, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(24.dp))
                        Bar(bar.count.toFloat() / max, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${bar.count}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(22.dp),
                        )
                    }
                }
            }
        }
        Panel("Pool · ${state.poolSize}") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (state.poolColorCounts.isEmpty()) {
                    Text("No picks yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val max = state.poolColorCounts.maxOf { it.score }
                    state.poolColorCounts.forEach { ColorBar(it.color, it.score / max, "${it.score.toInt()}") }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${state.poolCreatures} creatures · ${state.poolNonCreatures} spells",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedsContent(needs: List<String>, poolSize: Int) {
    when {
        poolSize == 0 -> Text(
            "Pick on raw value early.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        needs.isEmpty() -> Text(
            "Backbone looks solid ✓",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            needs.forEach { need ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.22f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(need, fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun ColorBar(color: Char, fraction: Double, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pip(color)
        Spacer(Modifier.width(6.dp))
        Bar(fraction.toFloat().coerceIn(0f, 1f), pipColor(color), Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(9.dp))
            Text(trailing, fontFamily = FontFamily.Monospace, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(22.dp))
        }
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(10.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
private fun PipRow(colors: List<Char>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { colors.forEach { Pip(it) } }
}

@Composable
private fun Pip(color: Char, size: Dp = 16.dp) {
    val painter = manaPainter(color)
    if (painter != null) {
        Image(painter, contentDescription = color.toString(), modifier = Modifier.size(size))
    } else {
        // Fallback: colored disc with a centered letter (rare — symbols are bundled).
        Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(pipColor(color)), contentAlignment = Alignment.Center) {
            Text(
                color.toString(),
                fontSize = 10.sp,
                lineHeight = 10.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                color = if (color == 'W') Color(0xFF333333) else Color(0xFF111111),
            )
        }
    }
}

/** Loads the bundled official Scryfall mana symbol SVG for a color. */
@Composable
private fun manaPainter(color: Char): Painter? {
    val density = LocalDensity.current
    return remember(color) {
        runCatching { useResource("symbols/$color.svg") { loadSvgPainter(it, density) } }.getOrNull()
    }
}

/** Wraps content so hovering it shows the card's art (Scryfall image), loaded lazily. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardPreview(imageUrl: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (imageUrl.isNullOrBlank()) {
        Box(modifier) { content() }
        return
    }
    TooltipArea(
        modifier = modifier,
        delayMillis = 300,
        tooltip = {
            val bitmap = rememberCardImage(imageUrl)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(232.dp, 323.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        },
        content = content,
    )
}

@Composable
private fun rememberCardImage(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    return produceState<ImageBitmap?>(initialValue = null, url) {
        value = CardImageLoader.load(url)
    }.value
}

// ---- small helpers -------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, width: Dp? = null, weight: Float? = null) {
    Text(
        text,
        modifier = cellModifier(width, weight),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MonoCell(
    text: String,
    width: Dp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text,
        modifier = Modifier.width(width),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = weight,
        color = color,
    )
}

private fun androidx.compose.foundation.layout.RowScope.cellModifier(width: Dp?, weight: Float?): Modifier = when {
    weight != null -> Modifier.weight(weight)
    width != null -> Modifier.width(width)
    else -> Modifier
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private fun Double?.asPct(): String = this?.let { "%.1f".format(it * 100) } ?: "—"
private fun Double?.as1dp(): String = this?.let { "%.1f".format(it) } ?: "—"
private fun Double?.asInt(): String = this?.let { "%.0f".format(it) } ?: "—"
