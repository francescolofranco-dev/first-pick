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
    fun `loads bundled MKM model and ranks bombs over basics P1P1`() = runBlocking {
        val repo = PickNetRepository(cacheDir = createTempDirectory("picknet-test"))
        repo.load("MKM", "PremierDraft")
        val net = assertNotNull(repo.netFor("MKM", "PremierDraft"))
        assertEquals("MKM", net.set)

        val ranked = net.score(emptyList(), listOf("Mountain", "Aurelia's Vindicator"))
        assertEquals("Aurelia's Vindicator", ranked[0].first)

        // Wrong format/set must not leak the loaded model.
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
