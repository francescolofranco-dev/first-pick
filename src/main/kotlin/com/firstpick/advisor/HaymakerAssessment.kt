package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics

/** The same effect and performance rules apply to every set; mana is checked separately. */
internal object HaymakerAssessment {
    fun reason(card: RankedCard, metrics: SetMetrics, meta: CardMeta?): String? {
        if (meta == null || meta.isLand) return null
        val rating = card.rating ?: return null
        if (!rating.hasReliableWinRate) return null
        val z = metrics.z(rating.gihWr) ?: return null
        val improvement = rating.iwd
        val exceptional = if (improvement != null) z >= 1.5 && improvement >= 0.045 else z >= 2.0
        if (!exceptional) return null

        // A cheap card needs unusually strong evidence and a repeatable advantage engine.
        if (meta.cmc <= 3 && (
                z < 2.5 || improvement == null || improvement < 0.06 ||
                    !(meta.isRepeatableAdvantage || (meta.isPlaneswalker && meta.isCardDraw))
                )) return null
        return when {
            meta.isMassRemoval -> "Exceptional performance with broad board-clearing impact"
            meta.isPlaneswalker && (meta.isRemoval || meta.isCardDraw) ->
                "Exceptional performance with repeatable planeswalker interaction or card advantage"
            meta.isRepeatableAdvantage -> "Exceptional performance with repeatable card advantage"
            meta.isFinisher -> "Exceptional performance with late-game finishing potential"
            meta.isCardDraw -> "Exceptional performance with card-advantage potential"
            else -> null
        }
    }
}
