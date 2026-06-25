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
    /**
     * Extrapolate a current role count to a full deck, shrunk toward the role's
     * expected pace so tiny early pools don't produce wild estimates (e.g. one
     * removal at pool 2 no longer implies "22 removal"). [target] is the role's
     * full-draft goal, which doubles as the prior we shrink toward.
     */
    fun projected(count: Int, totalPicks: Int, target: Double): Double {
        if (poolSize < 1) return count.toDouble()
        val priorRate = target / totalPicks
        val w = DeckNeeds.PROJECTION_PRIOR
        val rate = (count + priorRate * w) / (poolSize + w)
        return rate * totalPicks
    }

    /** Human-readable list of what the deck is still short on (for the UI). */
    fun activeNeeds(totalPicks: Int): List<String> = buildList {
        if (projected(removal, totalPicks, DeckNeeds.TARGET_REMOVAL) < DeckNeeds.TARGET_REMOVAL) add("Removal")
        if (projected(creatures, totalPicks, DeckNeeds.TARGET_CREATURES) < DeckNeeds.TARGET_CREATURES) add("Creatures")
        if (projected(twoDrops, totalPicks, DeckNeeds.TARGET_TWO_DROPS) < DeckNeeds.TARGET_TWO_DROPS) add("2-drops")
        if (poolSize >= DeckNeeds.FIXING_MIN_POOL && fixing < DeckNeeds.TARGET_FIXING) add("Fixing")
        if (projected(finishers, totalPicks, DeckNeeds.TARGET_FINISHERS) < DeckNeeds.TARGET_FINISHERS) add("Finisher")
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

/** A card's additive deck-needs bonus (in value points, pre-ramp) plus the reasons that fired. */
data class NeedsResult(val points: Double, val reasons: List<String>)

/**
 * Scores how well a card fills the pool's current gaps as an ADDITIVE point bonus
 * (0 = neutral). The advisor scales it by draft progress, so early picks stay
 * value-driven and the deck-building pressure ramps up mid/late. Additive (not a
 * multiplier on the whole value) so the boost is a fixed, interpretable slot-filling
 * premium rather than an amplification of the arbitrary 50-point midpoint.
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

    /** Shrink strength for [PoolNeeds.projected] (picks of prior weight). */
    const val PROJECTION_PRIOR = 8.0

    // Additive point bonuses/penalties (before the draft-progress ramp).
    private const val PTS_NEEDS_REMOVAL = 9.0
    private const val PTS_REMOVAL_SATURATED = -7.0
    private const val PTS_NEEDS_CREATURES = 5.0
    private const val PTS_TWO_DROP_MAX = 8.0
    private const val PTS_NEEDS_FIXING = 6.0
    private const val PTS_NEEDS_FINISHER = 7.0
    private const val PTS_TOP_HEAVY = -8.0
    private const val PTS_FLOOR = -14.0
    private const val PTS_CEIL = 20.0

    fun evaluateCard(meta: CardMeta?, needs: PoolNeeds, totalPicks: Int): NeedsResult {
        if (meta == null) return NeedsResult(0.0, emptyList())
        val reasons = mutableListOf<String>()
        var pts = 0.0

        if (meta.isRemoval) {
            if (needs.projected(needs.removal, totalPicks, TARGET_REMOVAL) < TARGET_REMOVAL) {
                pts += PTS_NEEDS_REMOVAL; reasons += "Needs removal"
            } else if (needs.removal > REMOVAL_SATURATION) {
                pts += PTS_REMOVAL_SATURATED; reasons += "Removal saturated"
            }
        }
        if (meta.isCreature && needs.projected(needs.creatures, totalPicks, TARGET_CREATURES) < TARGET_CREATURES) {
            pts += PTS_NEEDS_CREATURES; reasons += "Needs creatures"
        }
        if (meta.isCreature && meta.cmc in 1..2) {
            val projected = needs.projected(needs.twoDrops, totalPicks, TARGET_TWO_DROPS)
            if (projected < TARGET_TWO_DROPS) {
                pts += min(PTS_TWO_DROP_MAX, (TARGET_TWO_DROPS - projected) * 2.0)
                reasons += "Fills 2-drop need"
            }
        }
        if (meta.isFixing && needs.poolSize >= FIXING_MIN_POOL && needs.fixing < TARGET_FIXING) {
            pts += PTS_NEEDS_FIXING; reasons += "Needs fixing"
        }
        if (meta.isFinisher && needs.projected(needs.finishers, totalPicks, TARGET_FINISHERS) < TARGET_FINISHERS) {
            pts += PTS_NEEDS_FINISHER; reasons += "Needs a finisher"
        }
        if (!meta.isLand && meta.cmc >= 5 && needs.topEnd >= TOP_HEAVY_THRESHOLD) {
            pts += PTS_TOP_HEAVY; reasons += "Top-heavy"
        }

        return NeedsResult(pts.coerceIn(PTS_FLOOR, PTS_CEIL), reasons.take(2))
    }
}
