package com.firstpick.advisor

import com.firstpick.cards.RankedCard

/** Pair indices refer to physical pack entries, so two copies remain two choices. */
object PairAdvisor {
    fun recommend(
        ranked: List<ScoredCard>,
        marginalScore: (RankedCard, RankedCard?) -> ScoredCard,
    ): Set<Int> {
        if (ranked.size < 2) return ranked.indices.toSet()
        // Never let synergy buy its way around the single-card feasibility guardrails.
        val viable = ranked.indices.filter { ranked[it].guardrail.modelPromotable }
        val candidates = if (viable.size >= 2) viable else ranked.indices.toList()
        val baseline = candidates.associateWith { marginalScore(ranked[it].card, null).value }
        var best = emptySet<Int>()
        var bestValue = Double.NEGATIVE_INFINITY
        for (a in candidates.indices) for (b in a + 1 until candidates.size) {
            val i = candidates[a]
            val j = candidates[b]
            val afterI = marginalScore(ranked[j].card, ranked[i].card)
            val afterJ = marginalScore(ranked[i].card, ranked[j].card)
            if (viable.size >= 2 && (!afterI.guardrail.modelPromotable || !afterJ.guardrail.modelPromotable)) continue
            // Same scoring context on each side: learned/deck-fit single-card scores stay
            // intact. Only marginal synergy, role demand and copy saturation are added.
            // Average both orders; a pair must not benefit merely from being scored first.
            val adjustment = ((afterI.value - baseline.getValue(j)) +
                (afterJ.value - baseline.getValue(i))).div(2).coerceIn(-3.0, 3.0)
            val value = ranked[i].value + ranked[j].value + adjustment
            if (value > bestValue) {
                bestValue = value
                best = setOf(i, j)
            }
        }
        return best.ifEmpty { if (viable.size < 2) candidates.take(2).toSet() else emptySet() }
    }
}
