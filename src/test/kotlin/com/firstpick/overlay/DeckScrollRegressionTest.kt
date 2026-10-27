package com.firstpick.overlay

import java.awt.image.BufferedImage
import kotlinx.serialization.json.*
import kotlin.test.*

class DeckScrollRegressionTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/overlay/$name")).readText()
    private val baseline = Json.parseToJsonElement(resource("deck-scroll-pool.json")).jsonArray.map {
        val card = it.jsonObject
        DeckCardCount(card.getValue("name").jsonPrimitive.content, card.getValue("count").jsonPrimitive.int)
    }.filterNot { it.name == "Mountain" }
    private val blank = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)

    @Test
    fun realBottomOcrCompletesAccountingAfterTheTopRowsWereRead() {
        // Top names/counts are transcribed from the live Arena screenshot. Bottom OCR
        // comes directly from deck-reader; no account identifiers or game images stored.
        val names = listOf(
            "Unsummon", "Mabel, Bitter Recluse", "Countersculpt", "Icy Reception", "Last Gasp",
            "Rank Rat", "Theorix Charm", "Twinned Vision", "Arni, Humble Scribe", "Infinite Coursework",
        )
        val target = names.map { DeckCardCount(it, 1) } + listOf(
            DeckCardCount("Proft, Sinister Mastermind", 1), DeckCardCount("Theoretical Necromancer", 2),
            DeckCardCount("Winter, Tormented Loner", 1), DeckCardCount("Hapatra, the Desert Frost", 1),
            DeckCardCount("Hapatra, the Desert Fang", 1), DeckCardCount("Ruric Thar, Biomagus", 1),
            DeckCardCount("Break Under Pressure", 1), DeckCardCount("Protege's Awakening", 2),
            DeckCardCount("Extended Absence", 1), DeckCardCount("Recursive Recruitment", 1),
            DeckCardCount("Apex Witchstalker", 1),
        )
        assertEquals(23, target.sumOf { it.count })
        val session = DeckGuidanceSession(target, baseline)
        val bottom = assertNotNull(DeckBuilderScreenInterpreter.interpret(
            assertNotNull(VisionOcrPayloadParser.parse(resource("deck-scroll-bottom-ocr.json"))),
        ))
        session.observe(blank, bottom.copy(deckRows = names.mapIndexed { index, name -> row(name, y = 0.22 + index * 0.04) }, poolCardTitles = emptyList()))
        val complete = session.observe(blank, bottom.copy(poolCardTitles = emptyList()))
        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, complete.status)
        assertEquals(23, complete.verifiedCards)
        assertEquals(1, session.currentCount("Proft, Sinister Mastermind"))
        assertEquals(2, session.currentCount("Theoretical Necromancer"))
        assertEquals(1, session.currentCount("Winter, Tormented Loner"))
        assertEquals(1, session.currentCount("Hapatra, the Desert Frost"))
        assertEquals(1, session.currentCount("Hapatra, the Desert Fang"))
        assertEquals(1, session.currentCount("Ruric Thar, Biomagus"))
        assertEquals(0, session.currentCount("Refute Destiny"))
        assertEquals(2, complete.remainingRemovals)
        assertEquals(0, complete.remainingAdds)
        assertEquals(1, complete.basicLandDelta)
        assertEquals(setOf("Murmuring Volume", "Apex Witchstalker"), complete.changes.map { it.name }.toSet())
    }

    @Test
    fun ambiguousRowsUseOnlyExhaustedVisibleDraftCapacity() {
        val frost = "Hapatra, the Desert Frost"
        val fang = "Hapatra, the Desert Fang"
        val clear = row("Hapatra, the Desert Fr... (", y = 0.3)
        val ambiguous = row("Hapatra, the Dese...", y = 0.4)
        val exact = DeckRowNameResolver(mapOf(frost to 1, fang to 1)).resolve(listOf(clear, ambiguous))
        assertEquals(frost, exact[clear])
        assertEquals(fang, exact[ambiguous])
        val uncertain = DeckRowNameResolver(mapOf(frost to 2, fang to 1)).resolve(listOf(clear, ambiguous))
        assertNull(uncertain[ambiguous])
        assertTrue(DeckRowNameResolver(mapOf(frost to 1, fang to 1)).resolve(listOf(ambiguous)).isEmpty())
    }

    @Test
    fun contemporaneousPoolZeroDisambiguatesTheRemainingRowAfterACut() {
        val frost = "Hapatra, the Desert Frost"
        val fang = "Hapatra, the Desert Fang"
        val remaining = row("Hapatra, the Dese...", 0.4)
        val resolver = DeckRowNameResolver(mapOf(frost to 1, fang to 1))
        assertEquals(fang, resolver.resolve(listOf(remaining), mapOf(frost to 0))[remaining])
        assertNull(resolver.resolve(listOf(remaining), mapOf(frost to 1))[remaining])
        val direct = row("Hapatra, the Desert Fr... (", 0.3)
        assertEquals(frost, resolver.resolve(listOf(direct), mapOf(frost to 0))[direct])
    }

    @Test
    fun joinedManaIsRemovedOnlyForDeckNamesAndAmbiguityStaysUnknown() {
        val resolver = DeckRowNameResolver(mapOf("Proft, Sinister Mastermind" to 1, "Ruric Thar, Biomagus" to 1))
        val shortened = row("Proft, Sinister Master... (", 0.3)
        val full = row("Ruric Thar, Biomagus Q", 0.4)
        assertEquals("Proft, Sinister Mastermind", resolver.resolve(listOf(shortened))[shortened])
        assertEquals("Ruric Thar, Biomagus", resolver.resolve(listOf(full))[full])
        assertTrue(resolver.resolve(listOf(row("Proft, Sinister Master... unrelated text", 0.3))).isEmpty())
        assertTrue(resolver.resolve(listOf(row("Proft, Sinister Master", 0.3))).isEmpty())
    }

    private fun row(name: String, y: Double) = VisibleDeckRowObservation(1, name,
        NormalizedRect(0.79, y, 0.20, 0.035), NormalizedRect(0.817, y, 0.14, 0.02), 1.0)
}
