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
        // Generic fallback when there's no typed reason.
        assertTrue(ratingsErrorMessage(null, "SOS").contains("SOS"))
    }

    /** A LogWatcher that emits canned lines instead of tailing a real file. */
    private class FakeWatcher(private vararg val lines: String) : LogWatcher(Path.of("/dev/null")) {
        override fun lines(fromStart: Boolean): Flow<String> = flowOf(*lines)
    }

    @Test
    fun pipelineScoresThePackFromALogLine() = runBlocking {
        // Seed disk caches for every format so the data layer is fully offline
        // regardless of the persisted format choice.
        val cache = createTempDirectory("fp-vm")
        for (f in listOf("PremierDraft", "QuickDraft", "TradDraft")) {
            Files.writeString(cache.resolve("ratings2_SOS_$f.json"), "[]")
            Files.writeString(cache.resolve("colorratings_SOS_$f.json"), "[]")
        }
        Files.writeString(cache.resolve("scryfall4_SOS.json"), "[]")

        val repo = CardRepository(SeventeenLandsClient(cacheDir = cache))
        val metaRepo = CardMetaRepository(ScryfallClient(cacheDir = cache))
        val archRepo = ArchetypeRepository(SeventeenLandsClient(cacheDir = cache))

        // Reuse the committed P1P1 snapshot fixture (14 cards, set SOS).
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
            // Ranks are 1..14 and the list is ordered (rank 1 first).
            assertEquals(1, ui.packCards.first().rank)
        } finally {
            scope.cancel()
        }
    }
}
