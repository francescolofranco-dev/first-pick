package com.firstpick.signals

import com.firstpick.advisor.WUBRG
import com.firstpick.cards.RankedCard

object SignalsEngine {

    fun openLanes(
        seen: Map<Pair<Int, Int>, List<Int>>,
        resolve: (Int) -> RankedCard,
    ): Map<Char, Double> = openLanesResolved(seen.mapValues { (_, ids) -> ids.map(resolve) })

    fun openLanesResolved(seen: Map<Pair<Int, Int>, List<RankedCard>>): Map<Char, Double> {
        val score = mutableMapOf<Char, Double>()
        for ((packPick, cards) in seen) {
            val (pack, pick) = packPick
            if (pack !in SIGNAL_PACKS) continue
            for (card in cards) {
                val rating = card.rating ?: continue
                val ata = rating.ata ?: continue
                val gih = rating.gihWr ?: continue
                val lateness = pick - ata
                if (lateness <= 0.0) continue
                val quality = (gih - SIGNAL_BASELINE).coerceAtLeast(0.0)
                val contribution = lateness * quality
                if (contribution <= 0.0) continue
                for (c in rating.color) if (c in WUBRG) score.merge(c, contribution, Double::plus)
            }
        }
        return score
    }

    private const val SIGNAL_BASELINE = 0.50
    private val SIGNAL_PACKS = setOf(1, 3)
}
