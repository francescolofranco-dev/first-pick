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
        // A real ScreenCaptureKit P1P1 frame (5/5/3 = 13 cards), downscaled + grayscaled.
        val grid = CardDetector.detect(fixture("p1p1-13cards.png"))
        assertNotNull(grid, "should detect a grid in a real draft frame")
        assertEquals(5, grid.cols.size, "P1P1 lays out five columns")
        assertEquals(3, grid.rows.size, "13 cards wrap to three rows")
    }

    @Test
    fun cardsAreInRowMajorPickOrder() {
        val grid = CardDetector.detect(fixture("p1p1-13cards.png"))!!
        val cards = grid.cards(13)
        assertEquals(13, cards.size, "13-card pack yields 13 rects (partial last row trimmed)")

        // Column 0 of each row shares an x; rows advance downward.
        assertEquals(cards[0].x, cards[5].x)
        assertEquals(cards[0].x, cards[10].x)
        assertTrue(cards[5].y > cards[0].y)
        assertTrue(cards[10].y > cards[5].y)
        // Within the first row, x strictly increases across the five columns.
        for (c in 1..4) assertTrue(cards[c].x > cards[c - 1].x, "col $c should be right of ${c - 1}")
    }

    @Test
    fun boxesAreCardSizedAndTightToTheGrid() {
        val grid = CardDetector.detect(fixture("p1p1-13cards.png"))!!
        val w = grid.imageW
        val h = grid.imageH
        val card = grid.cards(1).single()
        // ~9% of frame width, ~21% of frame height (measured on real frames).
        assertTrue(card.w in (0.07 * w).toInt()..(0.11 * w).toInt(), "card width ${card.w}")
        assertTrue(card.h in (0.18 * h).toInt()..(0.24 * h).toInt(), "card height ${card.h}")
        // Grid starts in from the left edge (cards are not flush against the window).
        assertTrue(card.x > (0.12 * w).toInt())
    }

    @Test
    fun returnsNullForATooSmallImage() {
        assertNull(CardDetector.detect(BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)))
    }

    @Test
    fun returnsNullForABlankFrame() {
        // A uniform frame has no card/gutter contrast, so no grid.
        assertNull(CardDetector.detect(BufferedImage(1280, 748, BufferedImage.TYPE_INT_RGB)))
    }
}
