package com.firstpick.overlay

import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CardDetectorTest {

    private fun fixture(name: String): BufferedImage =
        requireNotNull(javaClass.getResourceAsStream("/overlay/$name")) { "missing fixture $name" }
            .use { ImageIO.read(it) }

    @Test
    fun findsThePackGridInARealCaptureFrame() {
        val grid = CardDetector.detect(fixture("p1p1-13cards.png"), expectedCount = 13)
        assertNotNull(grid, "should detect a grid in a real draft frame")
        assertEquals(5, grid.cols.size, "P1P1 lays out five columns")
        assertEquals(3, grid.rows.size, "13 cards wrap to three rows")
    }

    @Test
    fun cardsAreInRowMajorPickOrder() {
        val grid = CardDetector.detect(fixture("p1p1-13cards.png"), expectedCount = 13)!!
        val cards = grid.cards(13)
        assertEquals(13, cards.size, "13-card pack yields 13 rects (partial last row trimmed)")

        assertEquals(cards[0].x, cards[5].x)
        assertEquals(cards[0].x, cards[10].x)
        assertTrue(cards[5].y > cards[0].y)
        assertTrue(cards[10].y > cards[5].y)
        for (c in 1..4) assertTrue(cards[c].x > cards[c - 1].x, "col $c should be right of ${c - 1}")
    }

    @Test
    fun boxesAreCardSizedAndTightToTheGrid() {
        val grid = CardDetector.detect(fixture("p1p1-13cards.png"), expectedCount = 13)!!
        val w = grid.imageW
        val h = grid.imageH
        val card = grid.cards(1).single()
        assertTrue(card.w in (0.07 * w).toInt()..(0.11 * w).toInt(), "card width ${card.w}")
        assertTrue(card.h in (0.18 * h).toInt()..(0.24 * h).toInt(), "card height ${card.h}")
        assertTrue(card.x > (0.12 * w).toInt())
    }

    @Test
    fun findsTheGridWhenTheSelectedCardGlowMergesColumns() {
        // MSH QuickDraft P1P1: Arena pre-selects a card whose highlight glow bridges the gap
        // between columns 1 and 2, and the dark MSH name bars shift the brightness bands
        // below the true card tops. 14 cards lay out 5/5/4.
        val frame = fixture("msh-p1p1-14cards.jpg")
        val grid = CardDetector.detect(frame, expectedCount = 14)
        assertNotNull(grid, "glow-merged columns must be split back apart")
        assertEquals(5, grid.cols.size)
        assertEquals(3, grid.rows.size)

        val cards = grid.cards(14)
        assertEquals(14, cards.size)
        val h = grid.imageH
        assertTrue(cards[0].y in (0.15 * h).toInt()..(0.25 * h).toInt(), "row 1 must anchor at the card top, not the art top (y=${cards[0].y})")
        assertTrue(cards[0].h in (0.18 * h).toInt()..(0.25 * h).toInt(), "card height must match the 5:7 aspect (h=${cards[0].h})")
    }

    @Test
    fun splitsRunsThatAreMultiplesOfTheTypicalWidth() {
        val split = CardDetector.splitMergedRuns(listOf(371..867, 898..1123, 1152..1387, 1425..1642))
        assertEquals(5, split.size)
        assertEquals(371, split[0].first)
        assertEquals(867, split[1].last)
        assertTrue(split[0].last < split[1].first, "split parts must not overlap")
    }

    @Test
    fun allCardsShareOneUniformWidth() {
        for ((name, count) in listOf("p1p1-13cards.png" to 13, "msh-p1p1-14cards.jpg" to 14)) {
            val cards = CardDetector.detect(fixture(name), expectedCount = count)!!.cards(count)
            assertEquals(1, cards.map { it.w }.distinct().size, "$name: seal sizes derive from rect widths, so widths must be uniform")
        }
    }

    @Test
    fun singleRowPackAnchorsToTheCardsNotTheBrightBackdrop() {
        // Late picks show one row of cards over the animated city backdrop; the backdrop's
        // bright structures must not hijack the column/row projections.
        val w = 1554
        val h = 992
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(30, 18, 12)
        g.fillRect(0, 0, w, h)
        val cardW = 190
        val cardH = 304
        val top = (0.12 * h).toInt()
        for (i in 0 until 5) {
            g.color = java.awt.Color(225, 220, 210)
            g.fillRect(110 + i * 215, top, cardW, cardH)
        }
        val rnd = java.util.Random(7)
        repeat(160) {
            g.color = java.awt.Color(200 + rnd.nextInt(55), 130 + rnd.nextInt(60), 40)
            g.fillRect(rnd.nextInt(w - 30), (0.55 * h).toInt() + rnd.nextInt((0.4 * h).toInt()), 8 + rnd.nextInt(40), 8 + rnd.nextInt(60))
        }
        g.dispose()

        val grid = CardDetector.detect(img, expectedCount = 5)
        assertNotNull(grid, "single-row pack must be detected")
        assertEquals(1, grid.rows.size)
        val card = grid.cards(5).first()
        assertTrue(kotlin.math.abs(card.y - top) < 40, "row must anchor at the cards (y=${card.y}, cards at $top)")
        assertTrue(card.y + card.h < (0.60 * h).toInt(), "rects must not reach into the backdrop")
    }

    @Test
    fun rejectsAHoveredMagnifiedFrameWhereRowsFuseAndTheGridShiftsDown() {
        // MTGA's magnified card preview + tooltip fill the gap between the two card rows, fusing
        // them into one tall bright band. The detector then bottom-anchors the grid far too low
        // (row 0 landing near mid-screen). Such a frame must be rejected so the overlay keeps its
        // last clean detection instead of painting seals at the bottom of the window.
        val w = 2560
        val h = 1496
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = java.awt.Color(20, 15, 12)
        g.fillRect(0, 0, w, h)
        // five columns, but one continuous tall lit region (fused rows) whose bottom sits low
        g.color = java.awt.Color(230, 225, 215)
        for (i in 0 until 5) g.fillRect(380 + i * 260, 460, 220, 514)
        g.dispose()

        assertNull(CardDetector.detect(img, expectedCount = 8))
    }

    @Test
    fun returnsNullForATooSmallImage() {
        assertNull(CardDetector.detect(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)))
    }

    @Test
    fun returnsNullForABlankFrame() {
        assertNull(CardDetector.detect(BufferedImage(1280, 748, BufferedImage.TYPE_INT_RGB)))
    }
}
