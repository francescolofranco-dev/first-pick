package com.firstpick.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val DeckManaCostWidth = 88.dp
private val ManaCostToken = Regex("\\{([^}]+)}|//")

internal fun manaCostTokens(cost: String): List<String> =
    ManaCostToken.findAll(cost).map { if (it.value == "//") "//" else it.groupValues[1] }.toList()

internal fun manaSymbolDescription(symbol: String): String {
    fun part(value: String): String = when (value) {
        "W" -> "white mana"
        "U" -> "blue mana"
        "B" -> "black mana"
        "R" -> "red mana"
        "G" -> "green mana"
        "C" -> "colorless mana"
        "S" -> "snow mana"
        "P" -> "2 life"
        else -> "$value generic mana"
    }
    return if (symbol == "//") "other half" else symbol.split('/').joinToString(" or ", transform = ::part)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManaCost(cost: String?, isLand: Boolean = false, modifier: Modifier = Modifier) {
    val tokens = remember(cost) { manaCostTokens(cost.orEmpty()) }
    val description = when {
        cost == "" || (isLand && cost == null) -> "No mana cost"
        cost == null -> "Mana cost unavailable"
        else -> "Mana cost: " + tokens.joinToString(", ", transform = ::manaSymbolDescription)
    }
    FlowRow(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (tokens.isEmpty()) {
            Text("–", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            tokens.forEach { symbol ->
                val painter = manaCostPainter(symbol)
                if (painter != null) {
                    Image(painter, contentDescription = null, modifier = Modifier.size(16.dp))
                } else {
                    Text(
                        if (symbol == "//") "/ /" else "{$symbol}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun manaCostPainter(symbol: String): Painter? {
    val density = LocalDensity.current
    return remember(symbol, density) {
        if (symbol == "//") null else runCatching {
            useResource("symbols/${symbol.replace('/', '_')}.svg") { loadSvgPainter(it, density) }
        }.getOrNull()
    }
}
