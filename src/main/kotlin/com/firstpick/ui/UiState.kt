package com.firstpick.ui

import com.firstpick.model.DraftFormat
import com.firstpick.model.DraftPhase

/** One row in the pack table. [value]/[isBomb]/[reasons] are populated in M3. */
data class PackCardUi(
    val grpId: Int,
    val originalIndex: Int = 0,
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
    val z: Double = 0.0,
    val breakdown: com.firstpick.advisor.ValueBreakdown? = null,
)

/** A single colored magnitude (signals bar, pool count). */
data class ColorScore(val color: Char, val score: Double)

/** One mana-curve column. */
data class CurveBar(val cmcLabel: String, val count: Int)

/** A color-pair archetype's set strength, flagged if it's the player's current lane. */
data class ArchetypeRow(val pair: String, val winRate: Double, val isLane: Boolean) {
    val label: String get() = "$pair ${guildName(pair)}".trim()
}

/** One card line in a built deck (deduplicated; [count] copies). */
data class DeckSpellUi(
    val name: String,
    val cmc: Int,
    val color: String,
    val gihWr: Double?,
    val imageUrl: String? = null,
    /** Number of copies of this card in the deck. */
    val count: Int = 1,
    /** Primary card type, e.g. "Creature", "Instant", "Land". */
    val typeLabel: String = "",
    /** Functional role tag, e.g. "Removal", "Fixing", "Finisher" — null if none. */
    val role: String? = null,
    val isLand: Boolean = false,
)

/**
 * WUBRG ordering rank for a card's color string, following the MTG Arena convention:
 * White, Blue, Black, Red, Green, then multicolor (gold), then colorless/lands last.
 */
fun deckColorRank(color: String): Int {
    val c = color.trim()
    return when {
        c.isEmpty() -> 7
        c.length > 1 -> 6
        else -> "WUBRG".indexOf(c[0]).let { if (it < 0) 7 else it }
    }
}

/**
 * Deck-list ordering: by mana value, then WUBRG color, then win rate — matching MTGA's
 * deck builder so cards are easy to cross-reference when adding/removing them in Arena.
 */
val deckSpellOrder: Comparator<DeckSpellUi> =
    compareBy({ it.cmc }, { deckColorRank(it.color) }, { -(it.gihWr ?: 0.0) })

/** A finished deck proposal for the post-draft builder. */
data class DeckOptionUi(
    /** Full color identity (base pair + splash), in WUBRG order — drives the pips. */
    val colors: String,
    /** The two base colors, for the guild name. */
    val basePair: String,
    /** The single splash color, or null for a clean two-color deck. */
    val splash: Char? = null,
    val tier: String,
    val type: String,
    val outlook: String,
    val power: Int,
    val creatures: Int,
    val removal: Int,
    val landLine: String,
    val spells: List<DeckSpellUi>,
    /** Drafted non-basic lands in the build (deduplicated). */
    val lands: List<DeckSpellUi> = emptyList(),
) {
    val title: String
        get() = guildName(basePair).ifBlank { basePair } + (splash?.let { " · splash $it" } ?: "")
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
    val topPairs: List<String> = emptyList(),
    val archetypes: List<ArchetypeRow> = emptyList(),
    // Deck needs (M6) — roles the pool is still short on.
    val deckNeeds: List<String> = emptyList(),
    // Post-draft deck builder (M7).
    val deckOptions: List<DeckOptionUi> = emptyList(),
    // Which 17Lands data source the user has selected (see [RatingsFormat]).
    val ratingsFormatChoice: String = RatingsFormat.PREMIER,
    // True while a simulated/demo draft is driving the app (not a live Arena log).
    val simulating: Boolean = false,
    // True when the in-progress demo is paused (resumable).
    val simPaused: Boolean = false,
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

/**
 * User-selectable source for 17Lands card-quality data, independent of the draft
 * format actually being played. Premier Draft is the deepest, most stable sample,
 * so it's the default even in Quick Draft (card quality transfers); power users can
 * override per the dropdown, including "Auto" to match the detected draft format.
 * A choice resolves to a 17Lands `format` query string via [resolve].
 */
object RatingsFormat {
    const val PREMIER = "PREMIER"
    const val QUICK = "QUICK"
    const val TRAD = "TRAD"
    const val AUTO = "AUTO"

    /** Selectable choices, in dropdown order. */
    val choices = listOf(PREMIER, AUTO, QUICK, TRAD)

    fun label(choice: String): String = when (choice) {
        PREMIER -> "Premier (default)"
        QUICK -> "Quick Draft"
        TRAD -> "Traditional"
        AUTO -> "Auto · match draft"
        else -> "Premier (default)"
    }

    /** Map a choice (+ the detected draft format, for [AUTO]) to a 17Lands format string. */
    fun resolve(choice: String, detected: DraftFormat): String = when (choice) {
        QUICK -> "QuickDraft"
        TRAD -> "TradDraft"
        AUTO -> when (detected) {
            DraftFormat.QUICK -> "QuickDraft"
            DraftFormat.TRADITIONAL -> "TradDraft"
            else -> "PremierDraft"
        }
        else -> "PremierDraft" // PREMIER and any unknown value
    }
}
