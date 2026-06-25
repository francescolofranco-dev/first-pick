package com.firstpick.sim

import com.firstpick.cards.CardRating
import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.core.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Built-in demo / test mode: generates a synthetic MTGA Quick draft as a stream of
 * real-shaped `Player.log` snapshot lines, so the whole live pipeline (EventParser →
 * DraftTracker → AdvisorEngine → UI) runs exactly as it would for a real draft.
 *
 * A normal draft = 3 packs of 15 (45 picks). The visible pack shrinks 15 → 1 as you
 * pick, the pool grows, and an auto-picker advances the draft every [pickInterval].
 * The picker is win-rate weighted with a light on-color bias so a coherent lane
 * forms — enough for the lane, archetype, and deck-needs panels to come alive.
 *
 * Real card IDs + win rates come from the set's 17Lands data, so the app resolves and
 * scores the simulated cards just like live ones. Requires a set with 17Lands data.
 */
class DraftSimulator(
    private val client: SeventeenLandsClient = SeventeenLandsClient(),
    private val pickInterval: Duration = 1900.milliseconds,
    private val random: Random = Random.Default,
) {
    fun simulate(set: String, format: String = "PremierDraft"): Flow<String> = flow {
        val cards = runCatching { client.fetch(set, format) }.getOrDefault(emptyList())
            .filter { it.mtgaId != null && it.gihWr != null }
        if (cards.size < CARDS_PER_PACK) {
            Log.warn(TAG, "not enough 17Lands data to simulate ${set.uppercase()} (${cards.size} cards)")
            return@flow
        }
        val byId = cards.associateBy { it.mtgaId!! }
        val eventName = "QuickDraftEmblem_${set.uppercase()}_20260101"
        val pool = ArrayList<Int>()

        for (p in 0 until PACKS) {
            val pack = cards.shuffled(random).take(CARDS_PER_PACK).toMutableList()
            while (pack.isNotEmpty()) {
                emit(snapshot(eventName, p, CARDS_PER_PACK - pack.size, pack.map { it.mtgaId!! }, pool, "PickNext"))
                delay(pickInterval)
                val picked = autoPick(pack, pool, byId)
                pool.add(picked.mtgaId!!)
                pack.remove(picked)
            }
        }
        emit(snapshot(eventName, PACKS - 1, CARDS_PER_PACK - 1, emptyList(), pool, "Complete"))
    }

    /** Win-rate weighted pick with a light bias toward the pool's leaning colors. */
    private fun autoPick(pack: List<CardRating>, pool: List<Int>, byId: Map<Int, CardRating>): CardRating {
        val poolColors = pool.mapNotNull { byId[it]?.color }.flatMap { it.asIterable() }.groupingBy { it }.eachCount()
        val weights = pack.map { c ->
            val base = ((c.gihWr ?: 0.5) - 0.40).coerceAtLeast(0.02)
            val onColor = pool.size >= 3 && c.color.any { poolColors.getOrDefault(it, 0) > 0 }
            base * base * (if (onColor) ON_COLOR_BIAS else 1.0)
        }
        val total = weights.sum()
        var r = random.nextDouble(total)
        for (i in pack.indices) {
            r -= weights[i]
            if (r <= 0.0) return pack[i]
        }
        return pack.last()
    }

    /** Build one BotDraft snapshot line, matching the real Player.log shape (nested escaped JSON). */
    private fun snapshot(
        eventName: String,
        pack0: Int,
        pick0: Int,
        packIds: List<Int>,
        pickedIds: List<Int>,
        status: String,
    ): String {
        fun arr(xs: List<Int>) = xs.joinToString(",") { "\\\"$it\\\"" }
        val payload = "{\\\"Result\\\":\\\"Success\\\",\\\"EventName\\\":\\\"$eventName\\\"," +
            "\\\"DraftStatus\\\":\\\"$status\\\",\\\"PackNumber\\\":$pack0,\\\"PickNumber\\\":$pick0," +
            "\\\"NumCardsToPick\\\":1,\\\"DraftPack\\\":[${arr(packIds)}]," +
            "\\\"PackStyles\\\":[],\\\"PickedCards\\\":[${arr(pickedIds)}],\\\"PickedStyles\\\":[]}"
        return "{\"CurrentModule\":\"BotDraft\",\"Payload\":\"$payload\"}"
    }

    companion object {
        private const val TAG = "Sim"
        const val PACKS = 3
        const val CARDS_PER_PACK = 15
        private const val ON_COLOR_BIAS = 1.8

        /** Sets offered in the demo launcher. Any set code with 17Lands data works — edit freely. */
        val SETS = listOf("DSK", "BLB", "OTJ")
    }
}
