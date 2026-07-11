package com.firstpick.overlay

import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackGeometryTest {

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/overlay/$name")) { "missing fixture $name" }
            .use { ImageIO.read(it) }

    @Test
    fun rectsAreRowMajorWithLeftAlignedLastRow() {
        val rects = PackGeometry.rects(PackGeometry.DEFAULT, 1470, 860, 13)
        assertEquals(13, rects.size)
        assertEquals(rects[0].x, rects[5].x, "row 2 starts at column 0")
        assertEquals(rects[0].x, rects[10].x, "partial row 3 is left-aligned")
        assertEquals(rects[0].y, rects[4].y, "row 1 is level")
        assertTrue(rects[5].y > rects[0].y && rects[10].y > rects[5].y)
        for (c in 1..4) assertTrue(rects[c].x > rects[c - 1].x)
        assertEquals(1, rects.map { it.w }.distinct().size, "uniform card widths")
    }

    @Test
    fun defaultCalibrationMatchesBothRealFrames() {
        // The default fractions must place every rect on the card actually rendered there in the
        // real capture frames — this is the uncalibrated fallback the overlay ships with.
        for ((name, count) in listOf("p1p1-13cards.png" to 13, "msh-p1p1-14cards.jpg" to 14)) {
            val img = fixture(name)
            val detected = CardDetector.detect(img, expectedCount = count)!!.cards(count)
            val predicted = PackGeometry.rects(PackGeometry.DEFAULT, img.width, img.height, count)
            for ((d, p) in detected.zip(predicted)) {
                assertTrue(abs(d.x - p.x) < 0.012 * img.width, "$name rect ${d.index} x: detected ${d.x}, predicted ${p.x}")
                assertTrue(abs(d.y - p.y) < 0.02 * img.height, "$name rect ${d.index} y: detected ${d.y}, predicted ${p.y}")
            }
        }
    }

    @Test
    fun calibrationDerivedFromOneFrameTransfersToTheOther() {
        // Grid fractions measured on one set/resolution must predict the other frame's grid —
        // that is what makes per-window-size calibration (instead of per-pack capture) sound.
        val a = fixture("p1p1-13cards.png")
        val b = fixture("msh-p1p1-14cards.jpg")
        val cal = PackGeometry.fromGrid(CardDetector.detect(a, expectedCount = 13)!!)
        assertNotNull(cal)
        val detected = CardDetector.detect(b, expectedCount = 14)!!.cards(14)
        val predicted = PackGeometry.rects(cal, b.width, b.height, 14)
        for ((d, p) in detected.zip(predicted)) {
            assertTrue(abs(d.x - p.x) < 0.012 * b.width, "rect ${d.index} x: detected ${d.x}, predicted ${p.x}")
            assertTrue(abs(d.y - p.y) < 0.02 * b.height, "rect ${d.index} y: detected ${d.y}, predicted ${p.y}")
        }
    }

    @Test
    fun sparseGridsAreNotTrustedForCalibration() {
        val grid = CardDetector.Grid(cols = listOf(100..200), rows = listOf(50..300), imageW = 1000, imageH = 600)
        assertNull(PackGeometry.fromGrid(grid))
    }

    @Test
    fun singleRowCalibrationFallsBackToDefaultPitchRatio() {
        val grid = CardDetector.Grid(
            cols = listOf(100..190, 202..292, 304..394, 406..496, 508..598),
            rows = listOf(120..252),
            imageW = 1000,
            imageH = 600,
        )
        val cal = PackGeometry.fromGrid(grid)!!
        val expected = PackGeometry.DEFAULT.rowPitch * (cal.cardH / PackGeometry.DEFAULT.cardH)
        assertTrue(abs(cal.rowPitch - expected) < 1e-4)
    }

    @Test
    fun storeRoundTripsAndOnlyMatchesTheExactSize() {
        val dir = Files.createTempDirectory("fp-cal")
        val store = PackGridCalibrationStore(dir.resolve("grid.json"))
        assertNull(store.get(1470, 860))

        store.put(1470, 860, PackGeometry.DEFAULT)
        assertEquals(PackGeometry.DEFAULT, store.get(1470, 860))

        // Arena's title bar is constant points, so fractions drift across sizes — no reuse,
        // not even at the same aspect ratio.
        assertNull(store.get(735, 430))
        assertNull(store.get(1470, 1100))

        // fresh instance reads what was persisted
        assertEquals(PackGeometry.DEFAULT, PackGridCalibrationStore(dir.resolve("grid.json")).get(1470, 860))
    }
}
