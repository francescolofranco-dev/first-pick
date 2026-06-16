package com.firstpick.cards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One card's 17Lands ratings, as returned by
 * `https://www.17lands.com/card_ratings/data`. Win rates are fractions (0..1).
 *
 * Crucially, [mtgaId] is the Arena grpId, so pack cards join to ratings directly.
 * Stats that lack a large enough sample come back null.
 */
@Serializable
data class CardRating(
    @SerialName("name") val name: String = "",
    @SerialName("mtga_id") val mtgaId: Int? = null,
    @SerialName("color") val color: String = "",
    @SerialName("rarity") val rarity: String = "",
    @SerialName("types") val types: List<String> = emptyList(),
    @SerialName("url") val imageUrl: String = "",

    @SerialName("avg_seen") val avgSeen: Double? = null,            // ALSA
    @SerialName("avg_pick") val avgPick: Double? = null,            // ATA
    @SerialName("win_rate") val gamesPlayedWinRate: Double? = null, // GP WR
    @SerialName("opening_hand_win_rate") val openingHandWinRate: Double? = null,
    @SerialName("drawn_win_rate") val drawnWinRate: Double? = null,
    @SerialName("ever_drawn_win_rate") val everDrawnWinRate: Double? = null,     // GIH WR
    @SerialName("ever_drawn_game_count") val everDrawnGameCount: Int = 0,        // sample size
    @SerialName("drawn_improvement_win_rate") val drawnImprovementWinRate: Double? = null, // IWD
) {
    /** Game-in-hand win rate — the headline quality metric. */
    val gihWr: Double? get() = everDrawnWinRate

    /** Average last seen at (lower = picked earlier by the table). */
    val alsa: Double? get() = avgSeen

    /** Average taken at. */
    val ata: Double? get() = avgPick

    /** Improvement when drawn. */
    val iwd: Double? get() = drawnImprovementWinRate

    /** True once the sample is large enough for the win rate to be meaningful. */
    val hasReliableWinRate: Boolean get() = gihWr != null && everDrawnGameCount >= MIN_GAMES

    companion object {
        const val MIN_GAMES = 200
    }
}
