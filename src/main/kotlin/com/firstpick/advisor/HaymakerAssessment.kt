package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import com.firstpick.cards.RankedCard
import com.firstpick.cards.SetMetrics
import com.firstpick.cards.SynergyIndex

/** Impact review, statistical confidence, and castability are separate decisions. */
internal object HaymakerAssessment {
    fun reason(
        card: RankedCard,
        metrics: SetMetrics,
        meta: CardMeta?,
        synergy: SynergyIndex?,
        deck: List<RankedCard>,
    ): String? {
        val review = synergy?.profile?.haymakers?.firstOrNull {
            SynergyIndex.normalize(it.name) == SynergyIndex.normalize(card.name)
        }
        if (review != null) {
            if (review.requiresSetup) {
                if (review.minimumSupport == 0) return null // Setup is not mechanically verifiable yet.
                val support = review.supportCards.map(SynergyIndex::normalize).toSet()
                val count = deck.count { it.grpId != card.grpId && SynergyIndex.normalize(it.name) in support }
                if (count < review.minimumSupport) return null
            }
            // A reviewed effect remains usable in catalog mode, but reliable poor results veto it.
            if (card.rating?.hasReliableWinRate == true && !exceptional(card, metrics)) return null
            return review.reason
        }
        // Cheap statistical standouts need an explicit late-game impact review.
        if (meta == null || meta.cmc <= 3 || !exceptional(card, metrics)) return null
        // Single-target removal or an expensive body alone is not evidence of takeover potential.
        if (!meta.isFinisher && !meta.isCardDraw) return null
        return "Exceptional performance with late-game finishing or card-advantage potential"
    }

    private fun exceptional(card: RankedCard, metrics: SetMetrics): Boolean {
        val rating = card.rating ?: return false
        if (!rating.hasReliableWinRate) return false
        val z = metrics.z(rating.gihWr) ?: return false
        val improvement = rating.iwd
        return if (improvement != null) z >= 1.5 && improvement >= 0.045 else z >= 2.0
    }
}
