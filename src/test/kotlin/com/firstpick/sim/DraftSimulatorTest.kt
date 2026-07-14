package com.firstpick.sim

import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.log.EventParser
import com.firstpick.model.DraftEvent
import com.firstpick.model.setCodeFromEventName
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class DraftSimulatorTest {

    @Test
    fun simulatesAFullParseableDraft() = runBlocking {
        val cache = createTempDirectory("fp-sim")
        val ratings = (1..20).joinToString(",", "{\"data\":[", "]}") { id ->
            val color = listOf("W", "U", "B", "R", "G")[id % 5]
            """{"name":"C$id","mtga_id":$id,"color":"$color","rarity":"common","ever_drawn_win_rate":0.5${id % 9},"ever_drawn_game_count":1000}"""
        }
        Files.writeString(cache.resolve("ratings3_TST_PremierDraft.json"), ratings)

        val sim = DraftSimulator(
            client = SeventeenLandsClient(cacheDir = cache),
            pickInterval = 1.milliseconds,
            random = Random(42),
        )
        val lines = sim.simulate("TST").toList()
        val parser = EventParser()
        val snaps = lines.map { parser.parse(it) }

        assertEquals(DraftSimulator.PACKS * DraftSimulator.CARDS_PER_PACK + 1, lines.size)
        assertTrue(snaps.all { it is DraftEvent.Snapshot }, "every simulated line must parse to a Snapshot")

        val first = snaps.first() as DraftEvent.Snapshot
        assertEquals("TST", setCodeFromEventName(first.eventName))
        assertEquals(1, first.pack)
        assertEquals(1, first.pick)
        assertEquals(15, first.packCards.size)
        assertTrue(first.pool.isEmpty())

        val poolSizes = snaps.map { (it as DraftEvent.Snapshot).pool.size }
        assertEquals(poolSizes.sorted(), poolSizes, "pool should only grow")

        val last = snaps.last() as DraftEvent.Snapshot
        assertTrue(last.complete, "the final snapshot marks the draft complete")
        assertEquals(45, last.pool.size)
    }
}
