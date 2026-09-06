package com.firstpick.overlay

import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CardRecognizerTest {

    private fun card(seed: Int, w: Int = 300, h: Int = 420): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color(30, 30, 30)
        g.fillRect(0, 0, w, h)
        g.dispose()
        val ax = (0.08 * w).toInt(); val ay = (0.11 * h).toInt()
        val aw = (0.84 * w).toInt(); val ah = (0.33 * h).toInt()
        for (y in 0 until ah) for (x in 0 until aw) {
            val r = (seed * 53 + x) % 256
            val gg = (seed * 97 + y) % 256
            val b = (seed * 151 + x + y) % 256
            img.setRGB(ax + x, ay + y, Color(r, gg, b).rgb)
        }
        return img
    }

    private fun fixture(name: String): BufferedImage =
        requireNotNull(javaClass.getResourceAsStream("/overlay/$name")) { "missing fixture $name" }
            .use { ImageIO.read(it) }

    @Test
    fun matchesEachRectToTheCardDrawnThere() {
        val n = 6
        val refs = (0 until n).map { CardRecognizer.ofCard(card(it + 1)) }

        val drawn = intArrayOf(3, 0, 5, 1, 4, 2)
        val cw = 300; val ch = 420; val cols = 3
        val frame = BufferedImage(cols * cw + 40, 2 * ch + 40, BufferedImage.TYPE_INT_RGB)
        val g = frame.createGraphics()
        g.color = Color.BLACK; g.fillRect(0, 0, frame.width, frame.height)
        val rects = ArrayList<CardDetector.CardRect>()
        for (i in 0 until n) {
            val rx = 10 + (i % cols) * (cw + 10)
            val ry = 10 + (i / cols) * (ch + 10)
            g.drawImage(card(drawn[i] + 1), rx, ry, null)
            rects.add(CardDetector.CardRect(i, rx, ry, cw, ch))
        }
        g.dispose()

        val assignment = CardRecognizer.match(frame, rects, refs)
        for (i in 0 until n) {
            assertEquals(drawn[i], assignment[i], "rect $i should match the card drawn there")
        }
    }

    @Test
    fun findsTheMinimumCostOneToOneAssignment() {
        val refs = listOf(CardRecognizer.ofCard(card(1)), CardRecognizer.ofCard(card(2)))
        val frame = BufferedImage(640, 440, BufferedImage.TYPE_INT_RGB)
        val g = frame.createGraphics()
        g.drawImage(card(2), 10, 10, null)
        g.drawImage(card(1), 330, 10, null)
        g.dispose()
        val rects = listOf(
            CardDetector.CardRect(10, 10, 10, 300, 420),
            CardDetector.CardRect(20, 330, 10, 300, 420),
        )

        val result = CardRecognizer.matchDetailed(frame, rects, refs)

        assertEquals(mapOf(10 to 1, 20 to 0), result.assignment)
        assertTrue(result.totalDistance >= 0.0)
        assertTrue(result.worstDistance >= 0.0)
        assertTrue(result.assignment.values.toSet().size == refs.size)
    }

    @Test
    fun distinguishesRepulsorBlastFromAvengersHangarInArenaCapture() {
        val frame = fixture("msh-p2p4-repulsor-avengers.jpg")
        val refs = listOf(
            CardRecognizer.ofCard(fixture("msh-repulsor-reference.jpg")),
            CardRecognizer.ofCard(fixture("msh-avengers-reference.jpg")),
        )
        val rects = listOf(
            CardDetector.CardRect(0, 0, 0, 228, 330),
            CardDetector.CardRect(1, 228, 0, 228, 330),
        )

        val assignment = CardRecognizer.match(frame, rects, refs)

        assertEquals(mapOf(0 to 0, 1 to 1), assignment)
    }

    @Test
    fun doesNotSwapArcaneOmensAndKilliansConfidence() {
        val frame = fixture("sos-arcane-killian-offset-capture.jpg")
        val referenceStrip = fixture("sos-arcane-killian-references.jpg")
        val refs = listOf(
            CardRecognizer.ofCard(referenceStrip.getSubimage(0, 0, 336, 468)),
            CardRecognizer.ofCard(referenceStrip.getSubimage(336, 0, 336, 468)),
        )
        val rects = listOf(
            CardDetector.CardRect(0, 0, 0, 220, 318),
            CardDetector.CardRect(1, 220, 0, 220, 318),
        )

        assertEquals(mapOf(0 to 0, 1 to 1), CardRecognizer.match(frame, rects, refs))
    }

    @Test
    fun missingSwampReferenceDoesNotDisplaceWhiteTigerOrSwordsman() {
        val frame = fixture("msh-p3p4-swordsman-white-tiger-swamp.jpg")
        val referenceStrip = fixture("msh-p3p4-swordsman-white-tiger-references.jpg")
        val refs = listOf(
            CardRecognizer.ofCard(referenceStrip.getSubimage(0, 0, 336, 468)),
            CardRecognizer.ofCard(referenceStrip.getSubimage(336, 0, 336, 468)),
            null,
        )
        val rects = listOf(
            CardDetector.CardRect(0, 0, 0, 226, 331),
            CardDetector.CardRect(1, 226, 0, 226, 331),
            CardDetector.CardRect(2, 452, 0, 226, 331),
        )

        val assignment = CardRecognizer.match(frame, rects, refs)

        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2), assignment)
    }

    @Test
    fun avoidsTheLocallyCheapestPairWhenItMakesThePackWorse() {
        val assignment = CardRecognizer.minimumAssignment(
            arrayOf(
                doubleArrayOf(1.0, 2.0),
                doubleArrayOf(1.1, 100.0),
            ),
        )

        assertEquals(listOf(1, 0), assignment.toList())
    }

    @Test
    fun reflowsSidewaysRoomReferencesToMatchArenasStackedLayout() {
        val frame = fixture("dsk-room-cycle.jpg")
        val referenceStrip = fixture("dsk-room-references.jpg")
        val rawReferences = listOf(
            CardRecognizer.ofCard(referenceStrip.getSubimage(0, 0, 336, 468)),
            CardRecognizer.ofCard(referenceStrip.getSubimage(336, 0, 336, 468)),
            null,
        )
        val refs = listOf(
            CardRecognizer.ofCard(referenceStrip.getSubimage(0, 0, 336, 468), isRoom = true),
            CardRecognizer.ofCard(referenceStrip.getSubimage(336, 0, 336, 468), isRoom = true),
            null,
        )
        val rects = listOf(
            CardDetector.CardRect(0, 0, 0, 219, 334),
            CardDetector.CardRect(1, 219, 0, 219, 334),
            CardDetector.CardRect(2, 438, 0, 219, 334),
        )

        assertEquals(mapOf(0 to 2, 1 to 0, 2 to 1), CardRecognizer.match(frame, rects, rawReferences))
        val result = CardRecognizer.matchDetailed(frame, rects, refs)

        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2), result.assignment)
    }
}
