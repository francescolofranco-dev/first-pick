package com.firstpick.sim

import com.firstpick.cards.CardRating
import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.core.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class DraftSimulator(
    private val client: SeventeenLandsClient = SeventeenLandsClient(),
    private val pickInterval: Duration = 1900.milliseconds,
    private val random: Random = Random.Default,
) {
    fun simulate(
        set: String,
        format: String = "PremierDraft",
        paused: StateFlow<Boolean> = MutableStateFlow(false),
    ): Flow<String> = flow {
        val cards = runCatching { client.fetch(set, format) }.getOrDefault(emptyList())
            .filter { it.mtgaId != null && it.gihWr != null }
        if (cards.size < CARDS_PER_PACK) {
            Log.warn(TAG, "not enough 17Lands data to simulate ${set.uppercase()} (${cards.size} cards)")
            return@flow
        }
        val byId = cards.associateBy { it.mtgaId!! }
        val eventName = "QuickDraftEmblem_${set.uppercase()}_20260101"
        val pools = Array(SEATS) { ArrayList<Int>() }

        for (round in 0 until PACKS) {
            val held = Array(SEATS) { cards.shuffled(random).take(CARDS_PER_PACK).toMutableList() }
            val clockwise = round % 2 == 0
            for (pick in 0 until CARDS_PER_PACK) {
                emit(snapshot(eventName, round, pick, held[YOU].map { it.mtgaId!! }, pools[YOU], "PickNext"))
                paused.first { !it }
                delay(pickInterval)
                for (seat in 0 until SEATS) {
                    val pack = held[seat]
                    if (pack.isEmpty()) continue
                    val picked = autoPick(pack, pools[seat], byId)
                    pools[seat].add(picked.mtgaId!!)
                    pack.remove(picked)
                }
                rotate(held, clockwise)
            }
        }
        emit(snapshot(eventName, PACKS - 1, CARDS_PER_PACK - 1, emptyList(), pools[YOU], "Complete"))
    }

    private fun rotate(held: Array<MutableList<CardRating>>, clockwise: Boolean) {
        if (held.size < 2) return
        if (clockwise) {
            val last = held[held.size - 1]
            for (i in held.size - 1 downTo 1) held[i] = held[i - 1]
            held[0] = last
        } else {
            val first = held[0]
            for (i in 0 until held.size - 1) held[i] = held[i + 1]
            held[held.size - 1] = first
        }
    }

    private fun autoPick(pack: List<CardRating>, pool: List<Int>, byId: Map<Int, CardRating>): CardRating {
        val poolColors = pool.mapNotNull { byId[it]?.color }.flatMap { it.asIterable() }.groupingBy { it }.eachCount()
        val mainColors = if (pool.size >= COMMIT_AFTER)
            poolColors.entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet()
        else emptySet()
        val weights = pack.map { c ->
            val base = ((c.gihWr ?: 0.5) - 0.40).coerceAtLeast(0.02)
            val colors = c.color.filter { it in "WUBRG" }.toSet()
            val multiplier = when {
                mainColors.isEmpty() || colors.isEmpty() -> 1.0
                colors.all { it in mainColors } -> ON_COLOR_BIAS
                colors.any { it in mainColors } -> 1.0
                else -> OFF_COLOR_PENALTY
            }
            base * base * multiplier
        }
        val total = weights.sum()
        var r = random.nextDouble(total)
        for (i in pack.indices) {
            r -= weights[i]
            if (r <= 0.0) return pack[i]
        }
        return pack.last()
    }

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

        const val SEATS = 6
        private const val YOU = 0
        private const val COMMIT_AFTER = 4
        private const val ON_COLOR_BIAS = 3.0
        private const val OFF_COLOR_PENALTY = 0.2

        val SETS = listOf("DSK", "BLB", "OTJ")
    }
}
