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

/**
 * Built-in demo / test mode: generates a synthetic MTGA Quick draft as a stream of
 * real-shaped `Player.log` snapshot lines, so the whole live pipeline (EventParser →
 * DraftTracker → AdvisorEngine → UI) runs exactly as it would for a real draft.
 *
 * A normal draft = 3 packs of 15 (45 picks). This mimics a [SEATS]-seat table: each
 * round every seat opens a pack and the packs circulate (direction alternates per
 * round), so you only ever see — and pick from — a depleted subset, just like a real
 * draft (no longer "pick every card"). You and the bots auto-pick every [pickInterval],
 * win-rate weighted with a light on-color bias so coherent lanes form — enough for the
 * lane, archetype, and deck-needs panels to come alive.
 *
 * Real card IDs + win rates come from the set's 17Lands data, so the app resolves and
 * scores the simulated cards just like live ones. Requires a set with 17Lands data.
 */
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
        val pools = Array(SEATS) { ArrayList<Int>() } // running pool per seat (seat 0 = you)

        for (round in 0 until PACKS) {
            // Every seat opens a fresh pack; packs circulate, alternating direction per round.
            val held = Array(SEATS) { cards.shuffled(random).take(CARDS_PER_PACK).toMutableList() }
            val clockwise = round % 2 == 0
            for (pick in 0 until CARDS_PER_PACK) {
                // You see (and the advisor scores) the pack you currently hold.
                emit(snapshot(eventName, round, pick, held[YOU].map { it.mtgaId!! }, pools[YOU], "PickNext"))
                // Pause point: stay on the current pack (state preserved) until resumed.
                paused.first { !it }
                delay(pickInterval)
                // Every seat takes a card from its current pack (bots draft on-color too)...
                for (seat in 0 until SEATS) {
                    val pack = held[seat]
                    if (pack.isEmpty()) continue
                    val picked = autoPick(pack, pools[seat], byId)
                    pools[seat].add(picked.mtgaId!!)
                    pack.remove(picked)
                }
                // ...then passes its pack to the next seat.
                rotate(held, clockwise)
            }
        }
        emit(snapshot(eventName, PACKS - 1, CARDS_PER_PACK - 1, emptyList(), pools[YOU], "Complete"))
    }

    /** Pass every pack one seat along (clockwise = each seat receives from the previous one). */
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

    /** Win-rate weighted pick that commits to the seat's two main colors, like a real drafter. */
    private fun autoPick(pack: List<CardRating>, pool: List<Int>, byId: Map<Int, CardRating>): CardRating {
        val poolColors = pool.mapNotNull { byId[it]?.color }.flatMap { it.asIterable() }.groupingBy { it }.eachCount()
        // Once a seat has a few picks, lock onto its two most-drafted colors: on-color cards
        // get a strong boost, off-color cards a steep cut. (Earlier, any color ever picked
        // counted as on-color, so seats never committed and pools became 5-color soup.)
        val mainColors = if (pool.size >= COMMIT_AFTER)
            poolColors.entries.sortedByDescending { it.value }.take(2).map { it.key }.toSet()
        else emptySet()
        val weights = pack.map { c ->
            val base = ((c.gihWr ?: 0.5) - 0.40).coerceAtLeast(0.02)
            val colors = c.color.filter { it in "WUBRG" }.toSet()
            val multiplier = when {
                mainColors.isEmpty() || colors.isEmpty() -> 1.0   // undecided, or colorless
                colors.all { it in mainColors } -> ON_COLOR_BIAS  // fully on-color
                colors.any { it in mainColors } -> 1.0            // a splash — neutral
                else -> OFF_COLOR_PENALTY                          // fully off-color
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

        /** Seats at the table (you + bots). 6 packs circulate per round → real selection. */
        const val SEATS = 6
        private const val YOU = 0
        private const val COMMIT_AFTER = 4   // picks before a seat locks onto its two colors
        private const val ON_COLOR_BIAS = 3.0
        private const val OFF_COLOR_PENALTY = 0.2

        /** Sets offered in the demo launcher. Any set code with 17Lands data works — edit freely. */
        val SETS = listOf("DSK", "BLB", "OTJ")
    }
}
