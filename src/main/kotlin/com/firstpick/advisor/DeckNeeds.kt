package com.firstpick.advisor

import com.firstpick.cards.CardMeta
import kotlin.math.min

data class PoolNeeds(
    val creatures: Int,
    val twoDrops: Int,
    val removal: Int,
    val fixing: Int,
    val finishers: Int,
    val topEnd: Int,
    val poolSize: Int,
) {
    fun projected(count: Int, totalPicks: Int, target: Double): Double {
        if (poolSize < 1) return count.toDouble()
        val priorRate = target / totalPicks
        val w = DeckNeeds.PROJECTION_PRIOR
        val rate = (count + priorRate * w) / (poolSize + w)
        return rate * totalPicks
    }

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

data class NeedsResult(val points: Double, val reasons: List<String>)

object DeckNeeds {


    const val TARGET_REMOVAL = 6.0
    const val REMOVAL_SATURATION = 6
    const val TARGET_CREATURES = 14.0


    const val TARGET_TWO_DROPS = 8.0
    const val TARGET_FIXING = 3
    const val FIXING_MIN_POOL = 12
    const val TARGET_FINISHERS = 2.0


    const val TOP_HEAVY_THRESHOLD = 6

    const val PROJECTION_PRIOR = 8.0

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
