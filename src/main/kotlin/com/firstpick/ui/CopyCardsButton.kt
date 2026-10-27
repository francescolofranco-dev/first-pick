package com.firstpick.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale

internal fun cardsClipboardText(cards: List<DeckSpellUi>): String = buildString {
    append("Quantity\tCard\tGIH win rate\tMana cost\tMana value\tType\tRoles\tImage URL")
    for (card in cards) {
        val rate = card.gihWr?.takeIf { it.isFinite() }?.let {
            String.format(Locale.ROOT, "%.1f%%", it * 100)
        } ?: "N/A"
        val fields = listOf(card.count.toString(), card.name, rate,
            card.manaCost?.takeIf { it.isNotBlank() } ?: "N/A", card.cmc.toString(),
            card.typeLabel.ifBlank { "N/A" }, card.roles.joinToString(", ").ifBlank { "N/A" },
            card.imageUrl?.takeIf { it.isNotBlank() } ?: "N/A")
        append('\n')
        append(fields.joinToString("\t") { it.replace(Regex("[\\t\\r\\n]+"), " ") })
    }
}

@Composable
internal fun CopyCardsButton(cards: List<DeckSpellUi>, label: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(cards) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }
    val description = if (copied) "Copied $label" else "Copy $label"
    val color = if (copied) AppTheme.colors.positive else AppTheme.colors.textSecondary
    AppTooltip(title = description, tooltip = "Copy card quantities, names, GIH win rates, costs, types, roles, and image URLs.") {
        AppButton(
            onClick = { clipboard.setText(AnnotatedString(cardsClipboardText(cards))); copied = true },
            modifier = Modifier.semantics { contentDescription = description },
            enabled = cards.isNotEmpty(),
            contentPadding = PaddingValues(6.dp),
        ) {
            Canvas(Modifier.size(16.dp)) {
                if (copied) {
                    drawLine(color, Offset(size.width * .15f, size.height * .5f), Offset(size.width * .4f, size.height * .8f), 2.dp.toPx())
                    drawLine(color, Offset(size.width * .4f, size.height * .8f), Offset(size.width * .9f, size.height * .2f), 2.dp.toPx())
                } else {
                    drawRect(color, Offset(size.width * .12f, size.height * .12f), Size(size.width * .55f, size.height * .6f), style = Stroke(1.dp.toPx()))
                    drawRect(color, Offset(size.width * .35f, size.height * .32f), Size(size.width * .55f, size.height * .6f), style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}
