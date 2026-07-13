package com.firstpick.advisor

import com.firstpick.model.PickNet

/**
 * Reorders the heuristic engine's scored pack by the learned pick model.
 *
 * The model is the better ranker (MKM holdout: 63.1% top-1 vs 32.3%), so its
 * order wins. The heuristic VALUEs stay on display but are transplanted
 * rank-preservingly along the model's order, keeping value monotonic with
 * rank — grades, confidence gaps, and the pack's value distribution all keep
 * their meaning. A card whose displayed value is no longer its own keeps its
 * score-breakdown tooltip, with the gap booked as an explicit "model
 * adjustment" line so the components still sum to the displayed grade.
 */
object PickNetRanker {
    /** Below this share of pack cards known to the model, don't trust it. */
    const val MIN_COVERAGE = 0.8

    fun rerank(net: PickNet, scored: List<ScoredCard>, poolNames: List<String>): List<ScoredCard>? {
        if (scored.size < 2) return null
        val known = scored.count { net.knows(it.card.name) }
        if (known < scored.size * MIN_COVERAGE) return null

        val netScore = net.score(poolNames, scored.map { it.card.name }).toMap()
        // Stable sort: unknown cards (-inf) and equal scores keep heuristic order.
        val byNet = scored.sortedByDescending { netScore[it.card.name] ?: Float.NEGATIVE_INFINITY }
        val valuesDesc = scored.map { it.value }.sortedDescending()
        val heuristicTop = scored.first().card.name
        return byNet.mapIndexed { i, s ->
            val v = valuesDesc[i]
            val promoted = i == 0 && s.card.name != heuristicTop
            s.copy(
                value = v,
                rawValue = v,
                // The card keeps its own component breakdown; the transplant gap becomes a
                // "model adjustment" so Final score still equals the displayed grade.
                breakdown = s.breakdown?.copy(finalScore = v, modelShift = v - s.value),
                reasons = if (promoted) (listOf(MODEL_PICK_REASON) + s.reasons).take(3) else s.reasons,
                modelRank = i + 1,
            )
        }
    }

    const val MODEL_PICK_REASON = "Model pick"
}
