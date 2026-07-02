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
    /**
     * Hybrid mana pip groups from the cost (e.g. {U/W} -> setOf('U','W')): each group is
     * payable by EITHER of its colors, not both. Empty for cards with no hybrid pips.
     */
    val hybridColorGroups: List<Set<Char>> = emptyList(),
)
