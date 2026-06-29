package com.firstpick.ui

import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.cards.FetchFailure
import com.firstpick.cards.ScryfallClient
import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.draft.DraftTracker
import com.firstpick.log.LogWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftViewModelTest {

    @Test
    fun errorMessagesDifferentiateFailureReasons() {
        assertTrue(ratingsErrorMessage(FetchFailure.RATE_LIMITED, "SOS").contains("rate-limit", true))
        assertTrue(ratingsErrorMessage(FetchFailure.OFFLINE, "SOS").contains("reach", true))
        assertTrue(ratingsErrorMessage(FetchFailure.SERVER_ERROR, "SOS").contains("issues", true))
        assertTrue(ratingsErrorMessage(FetchFailure.NOT_FOUND, "SOS").contains("SOS"))
        assertTrue(ratingsErrorMessage(FetchFailure.BAD_DATA, "SOS").contains("SOS"))
        assertTrue(ratingsErrorMessage(null, "SOS").contains("SOS"))
    }

    private class FakeWatcher(private vararg val lines: String) : LogWatcher(Path.of("/dev/null")) {
        override fun lines(fromStart: Boolean): Flow<String> = flowOf(*lines)
    }

    @Test
    fun deckSpellOrderSortsByManaValueThenWubrgColor() {
        fun spell(name: String, cmc: Int, color: String, gih: Double = 0.55) =
            DeckSpellUi(name = name, cmc = cmc, color = color, gihWr = gih)
        val unsorted = listOf(
            spell("g2", 2, "G"),
            spell("w2", 2, "W"),
            spell("gold2", 2, "WU"),
            spell("u1", 1, "U"),
            spell("r2", 2, "R"),
            spell("w1", 1, "W"),
            spell("b2", 2, "B"),
            spell("colorless2", 2, ""),
        )
        val ordered = unsorted.sortedWith(deckSpellOrder).map { it.name }
        assertEquals(listOf("w1", "u1", "w2", "b2", "r2", "g2", "gold2", "colorless2"), ordered)
    }

    @Test
    fun pipelineScoresThePackFromALogLine() = runBlocking {
        val cache = createTempDirectory("fp-vm")
        for (f in listOf("PremierDraft", "QuickDraft", "TradDraft")) {
            Files.writeString(cache.resolve("ratings2_SOS_$f.json"), "[]")
            Files.writeString(cache.resolve("colorratings_SOS_$f.json"), "[]")
        }
        Files.writeString(cache.resolve("scryfall5_SOS.json"), "[]")

        val repo = CardRepository(SeventeenLandsClient(cacheDir = cache))
        val metaRepo = CardMetaRepository(ScryfallClient(cacheDir = cache))
        val archRepo = ArchetypeRepository(SeventeenLandsClient(cacheDir = cache))

        val line = javaClass.getResourceAsStream("/quickdraft_first_snapshot.log")!!
            .bufferedReader().readText().trim()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val vm = DraftViewModel(
                scope = scope,
                watcher = FakeWatcher(line),
                tracker = DraftTracker(),
                repo = repo,
                metaRepo = metaRepo,
                archetypeRepo = archRepo,
            )
            vm.start()
            val ui = withTimeout(15_000) { vm.ui.first { it.packCards.isNotEmpty() } }
            assertEquals("SOS", ui.setCode)
            assertEquals(14, ui.packCards.size)
            assertEquals(1, ui.packCards.first().rank)
        } finally {
            scope.cancel()
        }
    }
}
