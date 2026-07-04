package com.firstpick.ui

import com.firstpick.model.DraftFormat
import com.firstpick.model.DraftPhase

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

data class ColorScore(val color: Char, val score: Double)

data class CurveBar(val cmcLabel: String, val count: Int)

data class ArchetypeRow(val pair: String, val winRate: Double, val isLane: Boolean) {
    val label: String get() = "$pair ${guildName(pair)}".trim()
}

data class DeckSpellUi(
    val name: String,
    val cmc: Int,
    val color: String,
    val gihWr: Double?,
    val imageUrl: String? = null,
    val count: Int = 1,
    val typeLabel: String = "",
    val role: String? = null,
    val isLand: Boolean = false,
)

fun deckColorRank(color: String): Int {
    val c = color.trim()
    return when {
        c.isEmpty() -> 7
        c.length > 1 -> 6
        else -> "WUBRG".indexOf(c[0]).let { if (it < 0) 7 else it }
    }
}

val deckSpellOrder: Comparator<DeckSpellUi> =
    compareBy({ it.cmc }, { deckColorRank(it.color) }, { it.name.lowercase() })

data class DeckOptionUi(
    val colors: String,
    val basePair: String,
    val splash: Char? = null,
    val theme: String? = null,
    val tier: String,
    val type: String,
    val outlook: String,
    val power: Int,
    val creatures: Int,
    val removal: Int,
    val landLine: String,
    val spells: List<DeckSpellUi>,
    val lands: List<DeckSpellUi> = emptyList(),
) {
    val title: String
        get() {
            val base = guildName(basePair).ifBlank { basePair } + (splash?.let { " · splash $it" } ?: "")
            return theme?.let { "$base · $it" } ?: base
        }
}

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
    val laneColors: List<Char> = emptyList(),
    val openLanes: List<ColorScore> = emptyList(),
    val manaCurve: List<CurveBar> = emptyList(),
    val poolColorCounts: List<ColorScore> = emptyList(),
    val poolCreatures: Int = 0,
    val poolNonCreatures: Int = 0,
    val lanePair: String? = null,
    val topPairs: List<String> = emptyList(),
    val archetypes: List<ArchetypeRow> = emptyList(),
    val deckNeeds: List<String> = emptyList(),
    val deckOptions: List<DeckOptionUi> = emptyList(),
    val ratingsFormatChoice: String = RatingsFormat.PREMIER,
    val simulating: Boolean = false,
    val simPaused: Boolean = false,
    val synergyTier: String? = null,
    val researchedSets: List<String> = emptyList(),
    val groundedSets: List<String> = emptyList(),
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

object RatingsFormat {
    const val PREMIER = "PREMIER"
    const val QUICK = "QUICK"
    const val TRAD = "TRAD"
    const val AUTO = "AUTO"

    val choices = listOf(PREMIER, AUTO, QUICK, TRAD)

    fun label(choice: String): String = when (choice) {
        PREMIER -> "Premier (default)"
        QUICK -> "Quick Draft"
        TRAD -> "Traditional"
        AUTO -> "Auto · match draft"
        else -> "Premier (default)"
    }

    fun resolve(choice: String, detected: DraftFormat): String = when (choice) {
        QUICK -> "QuickDraft"
        TRAD -> "TradDraft"
        AUTO -> when (detected) {
            DraftFormat.QUICK -> "QuickDraft"
            DraftFormat.TRADITIONAL -> "TradDraft"
            else -> "PremierDraft"
        }
        else -> "PremierDraft"
    }
}
