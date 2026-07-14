package com.firstpick.advisor

import com.firstpick.model.PickNet


object PickNetRanker {

    const val MIN_COVERAGE = 0.8

    fun rerank(net: PickNet, scored: List<ScoredCard>, poolNames: List<String>): List<ScoredCard>? {
        if (scored.size < 2) return null
        val known = scored.count { net.knows(it.card.name) }
        if (known < scored.size * MIN_COVERAGE) return null

        val netScore = net.score(poolNames, scored.map { it.card.name }).toMap()

        val byNet = scored.sortedByDescending { netScore[it.card.name] ?: Float.NEGATIVE_INFINITY }
        val valuesDesc = scored.map { it.value }.sortedDescending()
        val heuristicTop = scored.first().card.name
        return byNet.mapIndexed { i, s ->
            val v = valuesDesc[i]
            val promoted = i == 0 && s.card.name != heuristicTop
            s.copy(
                value = v,
                rawValue = v,


                breakdown = s.breakdown?.copy(finalScore = v, modelShift = v - s.value),
                reasons = if (promoted) (listOf(MODEL_PICK_REASON) + s.reasons).take(3) else s.reasons,
                modelRank = i + 1,
            )
        }
    }

    const val MODEL_PICK_REASON = "Model pick"
}
