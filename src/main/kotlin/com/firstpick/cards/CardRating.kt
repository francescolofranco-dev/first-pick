package com.firstpick.cards

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CardRating(
    @SerialName("name") val name: String = "",
    @SerialName("mtga_id") val mtgaId: Int? = null,
    @SerialName("color") val color: String = "",
    @SerialName("rarity") val rarity: String = "",
    @SerialName("types") val types: List<String> = emptyList(),
    @SerialName("url") val imageUrl: String = "",

    @SerialName("avg_seen") val avgSeen: Double? = null,
    @SerialName("avg_pick") val avgPick: Double? = null,
    @SerialName("win_rate") val gamesPlayedWinRate: Double? = null,
    @SerialName("opening_hand_win_rate") val openingHandWinRate: Double? = null,
    @SerialName("drawn_win_rate") val drawnWinRate: Double? = null,
    @SerialName("ever_drawn_win_rate") val everDrawnWinRate: Double? = null,
    @SerialName("ever_drawn_game_count") val everDrawnGameCount: Int = 0,
    @SerialName("drawn_improvement_win_rate") val drawnImprovementWinRate: Double? = null,
) {
    val gihWr: Double? get() = everDrawnWinRate

    val alsa: Double? get() = avgSeen

    val ata: Double? get() = avgPick

    val iwd: Double? get() = drawnImprovementWinRate

    val hasReliableWinRate: Boolean get() = gihWr != null && everDrawnGameCount >= MIN_GAMES

    companion object {
        const val MIN_GAMES = 200
    }
}
