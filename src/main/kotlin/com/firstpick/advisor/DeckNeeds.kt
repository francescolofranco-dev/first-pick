package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import kotlin.math.min

/** A snapshot of the pool's "backbone" — the roles a balanced limited deck needs. */
data class PoolNeeds(
    val creatures: Int,
    val twoDrops: Int,
    val removal: Int,
    val fixing: Int,
    val finishers: Int,
    val topEnd: Int,
    val poolSize: Int,
) {
    /** Extrapolate a current count to a full 40-card draft. */
    fun projected(count: Int, totalPicks: Int): Double =
        if (poolSize < 1) count.toDouble() else count * (totalPicks.toDouble() / poolSize)

    /** Human-readable list of what the deck is still short on (for the UI). */
    fun activeNeeds(totalPicks: Int): List<String> = buildList {
        if (projected(removal, totalPicks) < DeckNeeds.TARGET_REMOVAL) add("Removal")
        if (projected(creatures, totalPicks) < DeckNeeds.TARGET_CREATURES) add("Creatures")
        if (projected(twoDrops, totalPicks) < DeckNeeds.TARGET_TWO_DROPS) add("2-drops")
        if (poolSize >= DeckNeeds.FIXING_MIN_POOL && fixing < DeckNeeds.TARGET_FIXING) add("Fixing")
        if (projected(finishers, totalPicks) < DeckNeeds.TARGET_FINISHERS) add("Finisher")
    }

    companion object {
        fun analyze(metas: List<CardMeta>, poolSize: Int): PoolNeeds {
            var creatures = 0; var twoDrops = 0; var removal = 0
            var fixing = 0; var finishers = 0; var topEnd = 0
            for (m in metas) {
                if (m.isFixing) fixing++
                if (m.isLand) continue
                if (m.isCreature) {
                    creatures++
                    if (m.cmc <= 2) twoDrops++
                }
                if (m.isRemoval) removal++
                if (m.isFinisher) finishers++
                if (m.cmc >= 5) topEnd++
            }
            return PoolNeeds(creatures, twoDrops, removal, fixing, finishers, topEnd, poolSize)
        }
    }
}

/** A card's deck-needs multiplier plus the reasons that fired. */
data class NeedsResult(val multiplier: Double, val reasons: List<String>)

/**
 * Scores how well a card fills the pool's current gaps. Returns a multiplier near
 * 1.0; the advisor scales its deviation from 1.0 by draft progress, so early picks
 * stay value-driven and the deck-building pressure ramps up in the mid/late draft.
 */
object DeckNeeds {
    const val TARGET_REMOVAL = 3.0
    const val REMOVAL_SATURATION = 6
    const val TARGET_CREATURES = 14.0
    const val TARGET_TWO_DROPS = 7.0
    const val TARGET_FIXING = 3
    const val FIXING_MIN_POOL = 12
    const val TARGET_FINISHERS = 2.0
    const val TOP_HEAVY_THRESHOLD = 4

    fun evaluateCard(meta: CardMeta?, needs: PoolNeeds, totalPicks: Int): NeedsResult {
        if (meta == null) return NeedsResult(1.0, emptyList())
        val reasons = mutableListOf<String>()
        var mult = 1.0

        if (meta.isRemoval) {
            if (needs.projected(needs.removal, totalPicks) < TARGET_REMOVAL) {
                mult *= 1.30; reasons += "Needs removal"
            } else if (needs.removal > REMOVAL_SATURATION) {
                mult *= 0.80; reasons += "Removal saturated"
            }
        }
        if (meta.isCreature && needs.projected(needs.creatures, totalPicks) < TARGET_CREATURES) {
            mult *= 1.18; reasons += "Needs creatures"
        }
        if (meta.isCreature && meta.cmc in 1..2) {
            val projected = needs.projected(needs.twoDrops, totalPicks)
            if (projected < TARGET_TWO_DROPS) {
                mult *= 1.0 + min(0.4, (TARGET_TWO_DROPS - projected) * 0.1)
                reasons += "Fills 2-drop need"
            }
        }
        if (meta.isFixing && needs.poolSize >= FIXING_MIN_POOL && needs.fixing < TARGET_FIXING) {
            mult *= 1.25; reasons += "Needs fixing"
        }
        if (meta.isFinisher && needs.projected(needs.finishers, totalPicks) < TARGET_FINISHERS) {
            mult *= 1.22; reasons += "Needs a finisher"
        }
        if (!meta.isLand && meta.cmc >= 5 && needs.topEnd >= TOP_HEAVY_THRESHOLD) {
            mult *= 0.75; reasons += "Top-heavy"
        }

        return NeedsResult(mult.coerceIn(0.6, 1.7), reasons.take(2))
    }
}
