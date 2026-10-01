package com.firstpick.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class ScryfallClientTest {

    private fun fraCards() = javaClass.getResourceAsStream("/cards/fra-scryfall.json")!!
        .bufferedReader().use { it.readText() }

    @Test
    fun preparationUsesCreatureCurveAndPipsButRetainsTheSpellsRoles() = runBlocking {
        val cache = createTempDirectory("fp-fra-meta")
        // Version 7 treated the combined creature/spell cost as the casting requirement.
        Files.writeString(cache.resolve("scryfall7_FRA.json"), "[]")
        val http = ScriptedHttpClient(
            StubHttpAction.Response(200, fraCards()),
            StubHttpAction.Response(404, ""),
        )
        val meta = ScryfallClient(cacheDir = cache, http = http).setMeta("FRA")
        val angel = meta.getValue("blossom-blessed angel")
        assertEquals(4, angel.cmc)
        assertTrue(angel.isCreature)
        assertTrue(angel.isEvasion)
        assertEquals(mapOf('W' to 1), angel.coloredPips)
        assertTrue(angel.hybridPips.isEmpty(), "the optional G/W prepare cost cannot require a green source")
        assertTrue(meta.getValue("pompous battlemage").isCardDraw)
        assertTrue(meta.getValue("variable chaser").isCardDraw)
        assertEquals(mapOf('R' to 1), meta.getValue("pompous battlemage").coloredPips)
        assertTrue(meta.getValue("pompous battlemage").heavyPipColors.isEmpty())
        val crafter = meta.getValue("heartwood crafter")
        assertTrue(crafter.isFixing)
        assertEquals(setOf('R', 'G'), crafter.producedColors)
        assertEquals(1, crafter.cmc)
        assertTrue(crafter.fixingRequiresAdditionalSpell)
        assertFalse(meta.getValue("hungering puppetbeast").fixingRequiresAdditionalSpell)
        assertTrue(Files.exists(cache.resolve("scryfall8_FRA.json")))
    }

    @Test
    fun catalogPreservesMissingArenaIdsAndNeverInventsStatistics() = runBlocking {
        val cache = createTempDirectory("fp-fra-catalog")
        val http = ScriptedHttpClient(StubHttpAction.Response(200, fraCards()))
        val client = ScryfallClient(cacheDir = cache, http = http)
        val catalog = client.cardCatalog("fra")
        assertEquals(5, catalog.cards.size)
        catalog.cards.forEach {
            assertNull(it.mtgaId)
            assertNull(it.gihWr)
            assertNull(it.alsa)
            assertEquals(0, it.everDrawnGameCount)
        }
        val cached = client.cardCatalog("FRA")
        assertEquals(catalog, cached)
        assertEquals(1, http.requestCount)
        assertEquals("(set:fra or (set:spg cn>=159 cn<=168))", ScryfallClient.limitedSetQuery("fra"))
    }

    @Test
    fun failedSecondPageKeepsThePreviousCompleteCatalog() = runBlocking {
        val cache = createTempDirectory("fp-fra-catalog-pages")
        val http = ScriptedHttpClient(
            StubHttpAction.Response(200, fraCards()),
            StubHttpAction.Response(200, """{"has_more":true,"next_page":"https://api.scryfall.com/cards/search?page=2","data":[{"name":"Incomplete first page"}]}"""),
            StubHttpAction.Response(404, ""),
        )
        val client = ScryfallClient(cacheDir = cache, http = http)
        val complete = client.cardCatalog("FRA")
        val path = cache.resolve("scryfall_catalog1_FRA.json")
        val saved = Files.readString(path)
        val refreshed = client.cardCatalog("FRA", forceRefresh = true)
        assertEquals(complete, refreshed)
        assertEquals(saved, Files.readString(path))
        assertEquals(3, http.requestCount)
    }

    @Test
    fun heartwoodFixingRequiresCreatingTokens() {
        assertTrue(roles("Creature", "Create a Heartwood token.").fixing)
        assertTrue(roles("Creature", "Create two tapped Heartwood tokens.").fixing)
        assertFalse(roles("Creature", "Sacrifice a Heartwood token: Draw a card.").fixing)
        assertFalse(roles("Instant", "Destroy target Heartwood token.").fixing)
    }


    @Test
    fun printedCostsAndFrontFaceCostsSurviveTheCache() = runBlocking {
        val cache = createTempDirectory("fp-mana-costs")
        val http = ScriptedHttpClient(
            StubHttpAction.Response(200, """{"data":[
                {"name":"Hybrid Creature","cmc":3,"type_line":"Creature","mana_cost":"{1}{W/U}{W/U}"},
                {"name":"Front // Back","cmc":2,"type_line":"Creature","card_faces":[{"mana_cost":"{1}{G}"},{"mana_cost":""}]},
                {"name":"Left // Right","cmc":5,"type_line":"Instant","mana_cost":"{1}{U} // {2}{B}"},
                {"name":"Free Artifact","cmc":0,"type_line":"Artifact","mana_cost":"{0}"},
                {"name":"Island","type_line":"Basic Land — Island","mana_cost":""}
            ]}"""),
            StubHttpAction.Response(404, "{}"),
        )
        val client = ScryfallClient(cacheDir = cache, http = http)
        val fetched = client.setMeta("TEST", listOf("Hybrid Creature", "Front", "Left", "Free Artifact", "Island"))
        assertEquals("{1}{W/U}{W/U}", fetched.getValue("hybrid creature").manaCost)
        assertEquals("{1}{G}", fetched.getValue("front").manaCost)
        assertEquals("{1}{U} // {2}{B}", fetched.getValue("left").manaCost)
        assertEquals("{0}", fetched.getValue("free artifact").manaCost)
        assertEquals("", fetched.getValue("island").manaCost)
        assertEquals(4, http.requestCount)

        assertEquals(fetched, client.setMeta("TEST"))
        assertEquals(4, http.requestCount, "A complete fresh cache must not fetch metadata again")
    }

    @Test
    fun legacyFreshCacheIsRefreshedToIncludeThePrintedCost() = runBlocking {
        val cache = createTempDirectory("fp-mana-upgrade")
        Files.writeString(cache.resolve("scryfall8_TEST.json"), legacyMetadata)
        val http = ScriptedHttpClient(
            StubHttpAction.Response(200, """{"data":[{"name":"Test Creature","cmc":3,"type_line":"Creature","mana_cost":"{1}{U}{U}"}]}"""),
            StubHttpAction.Response(404, "{}"),
        )
        val result = ScryfallClient(cacheDir = cache, http = http).setMeta("TEST", listOf("Test Creature"))
        assertEquals("{1}{U}{U}", result.getValue("test creature").manaCost)
        assertEquals(4, http.requestCount)
    }

    @Test
    fun legacyMetadataRemainsAvailableWhenCostRefreshFails() = runBlocking {
        val cache = createTempDirectory("fp-mana-fallback")
        Files.writeString(cache.resolve("scryfall8_TEST.json"), legacyMetadata)
        val http = ScriptedHttpClient(StubHttpAction.Response(400, "{}"))
        val result = ScryfallClient(cacheDir = cache, http = http).setMeta("TEST")
        assertTrue(result.getValue("test creature").isCreature)
        assertEquals(3, result.getValue("test creature").cmc)
        assertNull(result.getValue("test creature").manaCost)
        assertEquals(legacyMetadata, Files.readString(cache.resolve("scryfall8_TEST.json")))
    }

    private val legacyMetadata = """[{"name":"Test Creature","cmc":3,"creature":true,"land":false,"removal":false,"fixing":false,"finisher":false,"evasion":false,"draw":false}]"""

    private fun roles(
        type: String,
        text: String = "",
        cmc: Int = 3,
        power: Int = 0,
        produced: List<String> = emptyList(),
        removalTagged: Boolean = false,
        evasionTagged: Boolean = false,
        drawTagged: Boolean = false,
    ) = ScryfallClient.classifyRoles(type, text, cmc, power, produced, removalTagged, evasionTagged, drawTagged)

    @Test
    fun creatureAndLandComeFromTheTypeLine() {
        assertTrue(roles("Creature — Goblin").creature)
        assertFalse(roles("Instant").creature)
        assertTrue(roles("Basic Land — Mountain").land)
        assertFalse(roles("Artifact").land)
    }

    @Test
    fun removalFromOracleTextOrCuratedTag() {
        assertTrue(roles("Instant", "Destroy target creature.").removal)
        assertTrue(roles("Sorcery", "Exile target creature or planeswalker.").removal)
        assertTrue(roles("Instant", "Lightning Bolt deals 3 damage to any target.").removal)
        assertTrue(roles("Instant", "Deals 2 damage to target creature.").removal)
        assertFalse(roles("Instant", "Draw two cards.").removal)
        assertTrue(roles("Enchantment", "Some unusual removal wording.", removalTagged = true).removal)
    }

    @Test
    fun damageToAPlayerIsNotRemoval() {
        assertFalse(roles("Land", "{T}, Sacrifice Lonely Arroyo: It deals 1 damage to target player.").removal)
        assertFalse(roles("Sorcery", "Deals 2 damage to each opponent.").removal)
        assertFalse(roles("Instant", "Target player loses 3 life.").removal)
        assertFalse(roles("Instant", "Exile target card from a graveyard.").removal)
        assertFalse(roles("Sorcery", "Destroy target land.").removal)
    }

    @Test
    fun fixingFromDualManaOrAnyColor() {
        assertTrue(roles("Land", "{T}: Add W or U.", cmc = 0, produced = listOf("W", "U")).fixing)
        assertTrue(roles("Artifact", "{T}: Add one mana of any color.", cmc = 2).fixing)
        assertTrue(roles("Artifact", "When this dies, create a Treasure token.", cmc = 2).fixing)
        assertFalse(roles("Land", "{T}: Add G.", cmc = 0, produced = listOf("G")).fixing)
    }

    @Test
    fun finisherNeedsRealPower() {
        assertTrue(roles("Creature — Dragon", "Flying", cmc = 6, power = 5).finisher)
        assertTrue(roles("Creature — Beast", "Flying", cmc = 5, power = 4).finisher)
        assertFalse(roles("Creature — Bear", "", cmc = 2, power = 2).finisher)
        assertFalse(roles("Instant", "", cmc = 6, power = 0).finisher)
    }

    @Test
    fun evasionAndDrawDetected() {
        assertTrue(roles("Creature — Bird", "Flying").evasion)
        assertFalse(roles("Creature — Bear", "Vigilance").evasion)
        assertTrue(roles("Sorcery", "Draw a card.").draw)
        assertFalse(roles("Sorcery", "Gain 3 life.").draw)
    }

    @Test
    fun curatedTagsDriveEvasionAndDraw() {
        assertTrue(roles("Creature — Eldrazi", "It is just enormous.", evasionTagged = true).evasion)
        assertTrue(roles("Sorcery", "Investigate twice.", drawTagged = true).draw)
        assertFalse(roles("Creature — Bear", "Vigilance.", evasionTagged = false).evasion)
        assertTrue(roles("Sorcery", "Draw a card.", drawTagged = false).draw)
    }

    @Test
    fun hybridPipsAreExtractedAndCanonicallyOrdered() {
        assertEquals(listOf("WU"), ScryfallClient.hybridGroupsOf("{4}{U/W}"))
        assertEquals(listOf("WU"), ScryfallClient.hybridGroupsOf("{4}{W/U}"))
        assertEquals(listOf("WU", "BR"), ScryfallClient.hybridGroupsOf("{W/U}{1}{B/R}"))
        assertEquals(listOf("WU"), ScryfallClient.hybridGroupsOf("{U/W}{U/W}"))
    }

    @Test
    fun nonColorHybridAndPhyrexianPipsAreNotHybridGroups() {
        assertTrue(ScryfallClient.hybridGroupsOf("{2/W}").isEmpty())
        assertTrue(ScryfallClient.hybridGroupsOf("{U/P}").isEmpty())
        assertTrue(ScryfallClient.hybridGroupsOf("{3}{U}").isEmpty())
    }

    @Test
    fun heavyPipsAreColorsWithTwoOrMorePurePips() {
        assertEquals("W", ScryfallClient.heavyPipsOf("{3}{W}{W}"))
        assertEquals("", ScryfallClient.heavyPipsOf("{3}{W}"))
        assertEquals("WU", ScryfallClient.heavyPipsOf("{W}{W}{U}{U}"))
        assertEquals("", ScryfallClient.heavyPipsOf("{U/W}{U/W}"), "hybrid pips are payable another way")
    }

    @Test
    fun exactPureAndRepeatedHybridPipsAreRetained() {
        assertEquals("WWU", ScryfallClient.coloredPipsOf("{2}{W}{U}{W}"))
        assertEquals(listOf("WU", "WU", "BR"), ScryfallClient.hybridPipsOf("{W/U}{U/W}{B/R}"))
    }
}
