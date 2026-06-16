package com.firstpick.cards

/**
 * Structural + functional card facts the advisor's deck-needs logic uses, sourced
 * from Scryfall (17Lands provides none of this). Roles drive the "backbone" checks:
 * removal quota, fixing, finishers, curve.
 */
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
)
