package com.firstpick.model

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickNetRepositoryTest {

    @Test
    fun `bundled FRA model loads and scores real draft cards throughout a draft`() = runBlocking {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        assertEquals(listOf("FRA"), repo.bundledSets(listOf("fra", "ZZZ"), "PremierDraft"))
        repo.load("fra", "PremierDraft")
        val net = assertNotNull(repo.netFor("FRA", "PremierDraft"))
        assertEquals("FRA", net.set)
        assertEquals("PremierDraft", net.format)
        assertEquals(295, net.cards.size)
        val draftCards = listOf(
            "Forest", "Unsummon", "Last Gasp", "Saheeli, Jewel of Avishkar",
            "Konstrari Improviser", "Hungering Puppetbeast",
        )
        for (card in draftCards) assertTrue(net.knows(card), "FRA model must cover $card")

        val pools = listOf(
            emptyList(),
            listOf("Pompous Battlemage", "Pompous Battlemage", "Variable Chaser", "Unsummon"),
            listOf("Heartwood Crafter", "Heartwood Crafter", "Konstrari Improviser", "Hungering Puppetbeast"),
        )
        for (pool in pools) {
            val ranked = net.score(pool, net.cards)
            assertEquals(net.cards.toSet(), ranked.map { it.first }.toSet())
            assertTrue(ranked.isNotEmpty())
            assertTrue(ranked.all { it.second.isFinite() }, "FRA scores must be finite for pool $pool")
        }
    }

    @Test
    fun `bundled FRA model recognizes preparation creature names`() = runBlocking {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        repo.load("FRA", "PremierDraft")
        val net = assertNotNull(repo.netFor("FRA", "PremierDraft"))
        val preparationCards = listOf(
            "Blossom-Blessed Angel" to "Seed Suture",
            "Variable Chaser" to "Arc of Fortune",
            "Pompous Battlemage" to "Improvised Act",
            "Heartwood Crafter" to "Soul Tether",
        )
        for ((creature, spell) in preparationCards) {
            assertTrue(net.knows(creature), "FRA model must recognize the Arena name $creature")
            assertFalse(net.knows(spell), "Prepared spell $spell is not a separate draft card")
        }
        val ranked = net.score(emptyList(), preparationCards.map { it.first })
        assertEquals(preparationCards.size, ranked.size)
        assertTrue(ranked.all { it.second.isFinite() })
    }

    @Test
    fun `bundled FRA model is restricted to premier draft`(): Unit = runBlocking {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        repo.load("FRA", "PremierDraft")
        assertNotNull(repo.netFor("FRA", "PremierDraft"))
        assertNull(repo.netFor("HOB", "PremierDraft"))
        for (format in listOf("TradDraft", "QuickDraft", "Sealed")) {
            assertTrue(repo.bundledSets(listOf("FRA"), format).isEmpty())
            assertNull(repo.netFor("FRA", format))
            repo.load("FRA", format)
            assertNull(repo.netFor("FRA", format))
            assertNull(repo.netFor("FRA", "PremierDraft"))
        }
        repo.load("FRA", "PremierDraft")
        assertNotNull(repo.netFor("FRA", "PremierDraft"))
    }

    @Test
    fun `bundled HOB model loads and scores real draft cards`() = runBlocking {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        assertTrue("HOB" in repo.bundledSets(listOf("HOB"), "PremierDraft"))
        repo.load("HOB", "PremierDraft")
        val net = assertNotNull(repo.netFor("HOB", "PremierDraft"))
        assertEquals("HOB", net.set)
        assertEquals(193, net.cards.size)
        val pack = listOf("Forest", "Bilbo Baggins, Burglar")
        val ranked = net.score(emptyList(), pack)
        assertEquals(pack.toSet(), ranked.map { it.first }.toSet())
        assertTrue(ranked.all { it.second.isFinite() })
        assertEquals("Bilbo Baggins, Burglar", ranked.first().first)
        assertNull(repo.netFor("HOB", "TradDraft"))
    }

    @Test
    fun `loads bundled MKM model and ranks bombs over basics P1P1`() = runBlocking {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        repo.load("MKM", "PremierDraft")
        val net = assertNotNull(repo.netFor("MKM", "PremierDraft"))
        assertEquals("MKM", net.set)

        val ranked = net.score(emptyList(), listOf("Mountain", "Aurelia's Vindicator"))
        assertEquals("Aurelia's Vindicator", ranked[0].first)


        assertNull(repo.netFor("MKM", "TradDraft"))
        assertNull(repo.netFor("OTJ", "PremierDraft"))
    }

    @Test
    fun `bundledSets reports MKM for premier`() {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        assertTrue("MKM" in repo.bundledSets(listOf("MKM", "ZZZ"), "PremierDraft"))
        assertTrue(repo.bundledSets(listOf("ZZZ"), "PremierDraft").isEmpty())
    }

    @Test
    fun `missing model is a valid state`() = runBlocking {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        repo.load("ZZZ", "PremierDraft")
        assertNull(repo.netFor("ZZZ", "PremierDraft"))
    }
}
