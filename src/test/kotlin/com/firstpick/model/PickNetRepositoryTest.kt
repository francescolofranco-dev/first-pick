package com.firstpick.model

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickNetRepositoryTest {

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
