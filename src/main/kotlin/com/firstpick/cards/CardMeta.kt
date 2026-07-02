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
)
