package com.firstpick.cards

data class CardMeta(
    val name: String,
    val cmc: Int,
    val isCreature: Boolean,
    val isLand: Boolean,
    val isRemoval: Boolean = false,
    val isFixing: Boolean = false,
    val isFinisher: Boolean = false,
    val isEvasion: Boolean = false,
    val isCardDraw: Boolean = false,
    val hybridColorGroups: List<Set<Char>> = emptyList(),
    val producedColors: Set<Char> = emptySet(),
    val heavyPipColors: Set<Char> = emptySet(),
    /** Exact pure colored symbols from the printed mana cost. */
    val coloredPips: Map<Char, Int> = emptyMap(),
    /** One entry per hybrid symbol; duplicates preserve pip intensity. */
    val hybridPips: List<Set<Char>> = emptyList(),
    /** A prepared/Adventure spell must also be cast before this card provides fixing. */
    val fixingRequiresAdditionalSpell: Boolean = false,
    /** Printed cost in Scryfall's symbol notation; null means metadata is unavailable. */
    val manaCost: String? = null,
)
