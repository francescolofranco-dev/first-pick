package com.firstpick.ui

import com.firstpick.model.DraftFormat
import com.firstpick.model.DraftPhase

/** One row in the pack table. [value]/[isBomb]/[reasons] are populated in M3. */
data class PackCardUi(
    val grpId: Int,
    val rank: Int,
    val name: String,
    val color: String,
    val rarity: String,
    val gihWr: Double?,
    val alsa: Double?,
    val ata: Double?,
    val value: Double? = null,
    val isBomb: Boolean = false,
    val reasons: List<String> = emptyList(),
    val imageUrl: String? = null,
)

/** A single colored magnitude (signals bar, pool count). */
data class ColorScore(val color: Char, val score: Double)

/** One mana-curve column. */
data class CurveBar(val cmcLabel: String, val count: Int)

/** A color-pair archetype's set strength, flagged if it's the player's current lane. */
data class ArchetypeRow(val pair: String, val winRate: Double, val isLane: Boolean) {
    val label: String get() = "$pair ${guildName(pair)}".trim()
}

/** One card line in a built deck. */
data class DeckSpellUi(val name: String, val cmc: Int, val color: String, val gihWr: Double?, val imageUrl: String? = null)

/** A finished deck proposal for the post-draft builder. */
data class DeckOptionUi(
    val pair: String,
    val tier: String,
    val type: String,
    val outlook: String,
    val power: Int,
    val creatures: Int,
    val removal: Int,
    val landLine: String,
    val spells: List<DeckSpellUi>,
) {
    val title: String get() = guildName(pair).ifBlank { pair }
}

/** Common two-color guild names, for friendlier archetype labels. */
fun guildName(pair: String): String = when (pair) {
    "WU" -> "Azorius"
    "WB" -> "Orzhov"
    "WR" -> "Boros"
    "WG" -> "Selesnya"
    "UB" -> "Dimir"
    "UR" -> "Izzet"
    "UG" -> "Simic"
    "BR" -> "Rakdos"
    "BG" -> "Golgari"
    "RG" -> "Gruul"
    else -> ""
}

/** Everything the UI needs to render, derived from the live draft + 17Lands data. */
data class DraftUiState(
    val phase: DraftPhase = DraftPhase.IDLE,
    val setCode: String? = null,
    val format: DraftFormat = DraftFormat.UNKNOWN,
    val pack: Int = 0,
    val pick: Int = 0,
    val poolSize: Int = 0,
    val loadingRatings: Boolean = false,
    val dataError: String? = null,
    val packCards: List<PackCardUi> = emptyList(),
    // Sidebar panels (M4).
    val laneColors: List<Char> = emptyList(),
    val openLanes: List<ColorScore> = emptyList(),
    val manaCurve: List<CurveBar> = emptyList(),
    val poolColorCounts: List<ColorScore> = emptyList(),
    val poolCreatures: Int = 0,
    val poolNonCreatures: Int = 0,
    // Archetypes (M5).
    val lanePair: String? = null,
    val archetypes: List<ArchetypeRow> = emptyList(),
    // Deck needs (M6) — roles the pool is still short on.
    val deckNeeds: List<String> = emptyList(),
    // Post-draft deck builder (M7).
    val deckOptions: List<DeckOptionUi> = emptyList(),
) {
    val headline: String
        get() = when (phase) {
            DraftPhase.IDLE -> "Waiting for an MTG Arena draft…"
            DraftPhase.COMPLETE -> "${setCode ?: ""} · ${format.label} · Draft complete"
            DraftPhase.DRAFTING -> "${setCode ?: "?"} · ${format.label} · Pack $pack, Pick $pick"
        }
}

val DraftFormat.label: String
    get() = when (this) {
        DraftFormat.PREMIER -> "Premier Draft"
        DraftFormat.TRADITIONAL -> "Traditional Draft"
        DraftFormat.QUICK -> "Quick Draft"
        DraftFormat.SEALED -> "Sealed"
        DraftFormat.CUBE -> "Cube Draft"
        DraftFormat.UNKNOWN -> "Draft"
    }
