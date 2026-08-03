package com.firstpick.overlay

import java.awt.Color
import java.awt.BasicStroke
import java.awt.image.BufferedImage
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckPoolCopyDetectorTest {
    private fun title(column: Int = 0, row: Int = 0) = VisiblePoolCardTitleObservation(
        cardName = "Stock Up",
        titleBounds = NormalizedRect(0.033 + column * 0.153, if (row == 0) 0.279 else 0.650, 0.05, 0.015),
        column = column,
        row = row,
        confidence = 0.95,
    )

    @Test
    fun countsCenteredHollowDiamondsAsCopiesOutsideTheDeck() {
        for (drafted in 1..4) {
            for (current in 0..drafted) {
                val image = diamondImage(drafted = drafted, current = current)
                assertEquals(
                    drafted - current,
                    DeckPoolCopyDetector.count(image, title()),
                    "drafted=$drafted current=$current",
                )
            }
        }
    }

    @Test
    fun rejectsBrightShapesThatAreNotACenteredDiamondRun() {
        val image = blankImage()
        val graphics = image.createGraphics()
        graphics.color = Color(236, 236, 236)
        graphics.fillRect(150, 350, 110, 8)
        graphics.dispose()

        assertNull(DeckPoolCopyDetector.count(image, title()))
    }

    @Test
    fun titleAnchorsKeepDiamondsAlignedInAWiderViewport() {
        val image = diamondImage(drafted = 4, current = 2, width = 2100, height = 900)
        assertEquals(2, DeckPoolCopyDetector.count(image, title()))

        val bounds = requireNotNull(DeckPoolGeometry.cardBounds(title(), 2100.0 / 900.0))
        assertTrue(bounds.isValid())
        assertTrue(bounds.width < 0.11)
        assertTrue(bounds.height > 0.31)
    }

    @Test
    fun derivesAFullCardMarkerFromTheStableGridSlot() {
        val bounds = DeckPoolGeometry.cardBounds(title(column = 4, row = 1))
        requireNotNull(bounds)
        assertTrue(bounds.isValid())
        assertEquals(0.636, bounds.x, 0.0001)
        assertEquals(0.629, bounds.y, 0.0001)
        assertTrue(bounds.width > 0.13)
        assertTrue(bounds.height > 0.31)
    }

    private fun diamondImage(
        drafted: Int,
        current: Int,
        width: Int = 2560,
        height: Int = 1496,
    ): BufferedImage = blankImage(width, height).also { image ->
        repeat(drafted) { index -> paintDiamond(image, drafted, index, filled = index < current) }
    }

    private fun blankImage(
        width: Int = 2560,
        height: Int = 1496,
    ): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { image ->
        val graphics = image.createGraphics()
        graphics.color = Color(8, 8, 8)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
    }

    private fun paintDiamond(image: BufferedImage, drafted: Int, index: Int, filled: Boolean) {
        val referenceAspect = 2560.0 / 1496.0
        val aspect = image.width.toDouble() / image.height
        val horizontalScale = min(1.0, referenceAspect / aspect)
        val verticalScale = min(1.0, aspect / referenceAspect)
        val pitch = 0.0147 * horizontalScale * image.width
        val runCenter = ((0.033 + 0.0585 * horizontalScale) * image.width)
        val x = (runCenter + (index - (drafted - 1) / 2.0) * pitch).roundToInt()
        val y = ((0.279 - 0.0370 * verticalScale) * image.height).roundToInt()
        val radius = (pitch * 0.32).roundToInt().coerceAtLeast(5)
        val xs = intArrayOf(x, x + radius, x, x - radius)
        val ys = intArrayOf(y - radius, y, y + radius, y)
        val graphics = image.createGraphics()
        graphics.color = Color(236, 236, 244)
        if (filled) {
            graphics.fillPolygon(xs, ys, 4)
        } else {
            graphics.stroke = BasicStroke(2f)
            graphics.drawPolygon(xs, ys, 4)
        }
        graphics.dispose()
    }
}
