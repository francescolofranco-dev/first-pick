package com.firstpick.ui

import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.BasicLandIdentity
import com.firstpick.cards.BasicLandResolver
import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.cards.FetchFailure
import com.firstpick.cards.ScryfallClient
import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.cards.ScriptedHttpClient
import com.firstpick.cards.StubHttpAction
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
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DraftViewModelTest {

    @Test
    fun draftedCardsCarryThePrintedCostIntoTheUi() = runBlocking {
        val cache = createTempDirectory("fp-vm-mana")
        for (format in listOf("PremierDraft", "QuickDraft", "TradDraft")) {
            Files.writeString(cache.resolve("ratings3_DSK_$format.json"), """{"data":[
                {"name":"Test Creature","mtga_id":92174,"color":"U","types":["Creature"],"ever_drawn_win_rate":0.58,"ever_drawn_game_count":1000}
            ]}""")
            Files.writeString(cache.resolve("colorratings_DSK_$format.json"), "[]")
        }
        val metaRepo = CardMetaRepository().apply {
            index(listOf(CardMeta("Test Creature", cmc = 3, isCreature = true, isLand = false, manaCost = "{1}{U}{U}")), "DSK")
        }
        val line = """{"CurrentModule":"BotDraft","Payload":"{\"Result\":\"Success\",\"EventName\":\"QuickDraft_DSK_20260811\",\"DraftStatus\":\"PickNext\",\"PackNumber\":0,\"PickNumber\":3,\"NumCardsToPick\":1,\"DraftPack\":[\"92174\"],\"PackStyles\":[],\"PickedCards\":[\"92174\",\"92174\"],\"PickedStyles\":[]}"}"""
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val vm = DraftViewModel(
                scope = scope,
                watcher = FakeWatcher(line),
                tracker = DraftTracker(),
                repo = CardRepository(SeventeenLandsClient(cacheDir = cache)),
                metaRepo = metaRepo,
                archetypeRepo = ArchetypeRepository(SeventeenLandsClient(cacheDir = cache)),
            )
            vm.start()
            val ui = withTimeout(10_000) { vm.ui.first { it.draftPool.isNotEmpty() } }
            val card = ui.draftPool.single()
            assertEquals("{1}{U}{U}", card.manaCost)
            assertEquals(3, card.cmc, "Printed cost must not change sorting by mana value")
            assertEquals(2, card.count)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun newDraftClearsStateAndRestartsWatcherWithoutReplayingHistory() = runBlocking {
        val starts = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val live = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val watcher = object : LogWatcher(Path.of("/dev/null")) {
            override fun lines(fromStart: Boolean): Flow<String> = kotlinx.coroutines.flow.flow {
                starts.add(fromStart)
                if (fromStart) {
                    emit("""Draft.Notify {"SelfPack":2,"SelfPick":3,"PackCards":"10,20"}""")
                    emit("""PlayerDraftMakePick {"GrpIds":[10],"Pack":2,"Pick":3}""")
                }
                for (line in live) emit(line)
            }
        }
        val tracker = DraftTracker()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val vm = DraftViewModel(scope, watcher = watcher, tracker = tracker)
            vm.start()
            withTimeout(5_000) {
                while (tracker.state.value.pool.isEmpty()) kotlinx.coroutines.delay(10)
            }
            vm.newDraft()
            withTimeout(5_000) {
                while (starts.size < 2) kotlinx.coroutines.delay(10)
            }
            assertEquals(listOf(true, false), starts.toList())
            assertEquals(com.firstpick.model.DraftPhase.IDLE, vm.ui.value.phase)
            assertTrue(tracker.state.value.pool.isEmpty())
            assertTrue(tracker.state.value.seen.isEmpty())
            assertTrue(vm.ui.value.packCards.isEmpty())
            live.send("""Draft.Notify {"SelfPack":1,"SelfPick":1,"PackCards":"30,40"}""")
            withTimeout(5_000) {
                while (tracker.state.value.packCards.isEmpty()) kotlinx.coroutines.delay(10)
            }
            assertEquals(listOf(30, 40), tracker.state.value.packCards)
            assertTrue(tracker.state.value.pool.isEmpty())
        } finally {
            scope.cancel()
            live.close()
        }
    }

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
    fun landLineShowsTheProjectedBasicSourceSplit() {
        assertEquals("17 lands · 8W · 7U · 2 nonbasic", deckLandLine(17, 2, mapOf('W' to 8, 'U' to 7)))
        assertEquals("17 lands · Arena adds 17 basics", deckLandLine(17, 0, null))
    }

    @Test
    fun pipelineScoresThePackFromALogLine() = runBlocking {
        val cache = createTempDirectory("fp-vm")
        val ratings = """{
            "data": [
                {"name":"Split Room","mtga_id":102490,"types":["Enchantment - Room"]},
                {"name":"Ordinary Enchantment","mtga_id":102496,"types":["Enchantment"]}
            ]
        }""".trimIndent()
        for (f in listOf("PremierDraft", "QuickDraft", "TradDraft")) {
            Files.writeString(cache.resolve("ratings3_SOS_$f.json"), ratings)
            Files.writeString(cache.resolve("colorratings_SOS_$f.json"), "[]")
        }
        Files.writeString(cache.resolve("scryfall8_SOS.json"), "[]")

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
            val byId = ui.packCards.associateBy(PackCardUi::grpId)
            assertEquals(true, byId.getValue(102490).isRoom)
            assertEquals(false, byId.getValue(102496).isRoom)

            val guide = withTimeout(15_000) {
                vm.ui.first { it.setGuide?.setCode == "SOS" && it.setGuide.archetypes.isNotEmpty() }
            }.setGuide!!
            assertTrue(guide.principles.isNotEmpty())
            assertTrue(guide.archetypes.isNotEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun manualRetryBypassesFailedRequestGuardAndPublishesFreshMetadata() = runBlocking {
        val cache = createTempDirectory("fp-vm-retry")
        val response = """{"data":[
            {"name":"Split Room","mtga_id":102490,"types":["Enchantment - Room"],
             "ever_drawn_win_rate":0.58,"ever_drawn_game_count":600}
        ]}""".trimIndent()
        val http = ScriptedHttpClient(
            StubHttpAction.Offline,
            StubHttpAction.Offline,
            StubHttpAction.Offline,
            StubHttpAction.Response(200, response),
        )
        for (format in listOf("PremierDraft", "QuickDraft", "TradDraft")) {
            Files.writeString(cache.resolve("colorratings_SOS_$format.json"), "[]")
        }
        Files.writeString(cache.resolve("scryfall8_SOS.json"), "[]")
        val line = javaClass.getResourceAsStream("/quickdraft_first_snapshot.log")!!
            .bufferedReader().readText().trim()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val vm = DraftViewModel(
                scope = scope,
                watcher = FakeWatcher(line),
                tracker = DraftTracker(),
                repo = CardRepository(
                    SeventeenLandsClient(cacheDir = cache, http = http),
                    catalogClient = ScryfallClient(cacheDir = cache, http = ScriptedHttpClient(StubHttpAction.Response(404, ""))),
                ),
                metaRepo = CardMetaRepository(ScryfallClient(cacheDir = cache)),
                archetypeRepo = ArchetypeRepository(SeventeenLandsClient(cacheDir = cache)),
            )
            vm.start()

            val failed = withTimeout(10_000) {
                vm.ui.first { it.ratingsDataStatus == RatingsDataStatus.ERROR }
            }
            assertTrue(failed.canRetryRatings)
            assertNotNull(failed.dataError)

            vm.retryRatings()

            val refreshed = withTimeout(10_000) {
                vm.ui.first { it.ratingsDataStatus == RatingsDataStatus.FRESH }
            }
            assertNull(refreshed.dataError)
            assertEquals(1, refreshed.ratingsCardCount)
            assertEquals(1, refreshed.ratingsReliableCardCount)
            assertEquals(600, refreshed.ratingsMedianGamesPerCard)
            assertNotNull(refreshed.ratingsLastUpdated)
            assertEquals(4, http.requestCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pipelineRecognizesDskIslandButLeavesItUnratedAndLast() = runBlocking {
        val cache = createTempDirectory("fp-vm-basic")
        val ids = listOf(92174, 92100, 92352, 92131, 92262, 92237, 92333, 92126, 92325, 92084)
        val ratings = ids.mapIndexed { index, id ->
            """{"name":"Rated $index","mtga_id":$id,"ever_drawn_win_rate":${0.60 - index * 0.005},"ever_drawn_game_count":1000}"""
        }.joinToString(prefix = "{\"data\":[", postfix = "]}")
        for (format in listOf("PremierDraft", "QuickDraft", "TradDraft")) {
            Files.writeString(cache.resolve("ratings3_DSK_$format.json"), ratings)
            Files.writeString(cache.resolve("colorratings_DSK_$format.json"), "[]")
        }
        Files.writeString(cache.resolve("scryfall8_DSK.json"), "[]")

        val islandImage = "https://api.scryfall.com/cards/arena/92374?format=image&version=normal"
        val repo = CardRepository(
            client = SeventeenLandsClient(cacheDir = cache),
            basicLandResolver = BasicLandResolver { id ->
                if (id == 92374) BasicLandIdentity("Island", islandImage) else null
            },
        )
        val line = """{"CurrentModule":"BotDraft","Payload":"{\"Result\":\"Success\",\"EventName\":\"QuickDraft_DSK_20260811\",\"DraftStatus\":\"PickNext\",\"PackNumber\":0,\"PickNumber\":3,\"NumCardsToPick\":1,\"DraftPack\":[\"92174\",\"92100\",\"92352\",\"92131\",\"92262\",\"92237\",\"92333\",\"92126\",\"92325\",\"92084\",\"92374\"],\"PackStyles\":[],\"PickedCards\":[\"92253\",\"92153\",\"92214\"],\"PickedStyles\":[]}"} """.trim()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val vm = DraftViewModel(
                scope = scope,
                watcher = FakeWatcher(line),
                tracker = DraftTracker(),
                repo = repo,
                metaRepo = CardMetaRepository(ScryfallClient(cacheDir = cache)),
                archetypeRepo = ArchetypeRepository(SeventeenLandsClient(cacheDir = cache)),
            )
            vm.start()

            val ui = withTimeout(15_000) {
                vm.ui.first { state -> state.packCards.any { it.grpId == 92374 } }
            }
            val island = ui.packCards.single { it.grpId == 92374 }

            assertEquals(11, ui.packCards.size)
            assertEquals("Island", island.name)
            assertEquals(11, island.rank)
            assertEquals(10, island.originalIndex)
            assertTrue(island.isBasicLand)
            assertNull(island.value)
            assertNull(island.gihWr)
            assertNull(island.breakdown)
            assertNull(island.modelRank)
            assertEquals(listOf("Basic land", "Not rated"), island.reasons)
            assertEquals(islandImage, island.imageUrl)
            assertTrue(ui.packCards.dropLast(1).all { it.value != null })
        } finally {
            scope.cancel()
        }
    }
}
