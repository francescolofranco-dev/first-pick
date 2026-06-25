package com.firstpick.signals

import com.firstpick.advisor.WUBRG
import com.firstpick.cards.RankedCard

/**
 * Estimates how "open" each color is from the packs you've been passed. Seeing a
 * high-quality card later than its average-taken-at (ATA) position implies the
 * drafters upstream of you aren't in that color — so it's flowing your way. A high
 * score is a strong signal to move into (or stay in) that color.
 *
 * Ported from the reference tool's signal detection: only Pack 1 and Pack 3 are
 * considered (Pack 2 comes from the other direction, so it's noisier).
 */
object SignalsEngine {

    fun openLanes(
        seen: Map<Pair<Int, Int>, List<Int>>,
        resolve: (Int) -> RankedCard,
    ): Map<Char, Double> = openLanesResolved(seen.mapValues { (_, ids) -> ids.map(resolve) })

    /** Same signal, but from packs already resolved to [RankedCard] (live app + eval harness). */
    fun openLanesResolved(seen: Map<Pair<Int, Int>, List<RankedCard>>): Map<Char, Double> {
        val score = mutableMapOf<Char, Double>()
        for ((packPick, cards) in seen) {
            val (pack, pick) = packPick
            if (pack !in SIGNAL_PACKS) continue
            for (card in cards) {
                val rating = card.rating ?: continue
                val ata = rating.ata ?: continue
                val gih = rating.gihWr ?: continue
                val lateness = pick - ata           // later than expected = open
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
