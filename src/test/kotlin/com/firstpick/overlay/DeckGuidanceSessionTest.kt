package com.firstpick.overlay

import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeckGuidanceSessionTest {
    private val rowBounds = NormalizedRect(0.785, 0.30, 0.205, 0.035)
    private val poolTitle = VisiblePoolCardTitleObservation(
        cardName = "Stock Up",
        titleBounds = NormalizedRect(0.033, 0.279, 0.04, 0.015),
        column = 0,
        row = 0,
        confidence = 0.95,
    )

    @Test
    fun excessCopyProducesTheRedRowThenClearsSequentially() {
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount("Stock Up", 3)),
            draftPool = listOf(DeckCardCount("Stock Up", 4)),
        )
        val excess = session.observe(pipImage(4), screen(total = 41, rowCount = 4))

        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, excess.status)
        val remove = assertIs<DeckGuidanceVisualMark.RemoveRow>(excess.marks.single())
        assertEquals(4, remove.currentCount)
        assertEquals(rowBounds, remove.bounds)

        val exact = session.observe(pipImage(3), screen(total = 40, rowCount = 3))
        assertEquals(DeckGuidanceStatus.MATCHES, exact.status)
        assertTrue(exact.marks.isEmpty())
        assertEquals(3, session.currentCount("Stock Up"))
    }

    @Test
    fun missingCopyProducesOnlyTheGreenPoolCard() {
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount("Stock Up", 1)),
            draftPool = listOf(DeckCardCount("Stock Up", 1)),
        )

        val missing = session.observe(pipImage(0, drafted = 1), screen(total = 39, rowCount = null))
        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, missing.status)
        val add = assertIs<DeckGuidanceVisualMark.AddCard>(missing.marks.single())
        assertEquals(DeckPoolGeometry.cardBounds(poolTitle, 2560.0 / 1496.0), add.bounds)

        val exact = session.observe(pipImage(1, drafted = 1), screen(total = 40, rowCount = 1))
        assertEquals(DeckGuidanceStatus.MATCHES, exact.status)
        assertTrue(exact.marks.isEmpty())
    }

    @Test
    fun duplicateSequenceMovesFromRedToExactToGreen() {
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount("Stock Up", 1)),
            draftPool = listOf(DeckCardCount("Stock Up", 2)),
        )

        val excess = session.observe(pipImage(2, drafted = 2), screen(total = 41, rowCount = 2))
        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, excess.status)
        assertIs<DeckGuidanceVisualMark.RemoveRow>(excess.marks.single())

        val exact = session.observe(pipImage(1, drafted = 2), screen(total = 40, rowCount = 1))
        assertEquals(DeckGuidanceStatus.MATCHES, exact.status)
        assertTrue(exact.marks.isEmpty())
        assertEquals(1, session.currentCount("Stock Up"))

        val missing = session.observe(pipImage(0, drafted = 2), screen(total = 39, rowCount = null))
        assertEquals(DeckGuidanceStatus.NEEDS_CHANGES, missing.status)
        assertIs<DeckGuidanceVisualMark.AddCard>(missing.marks.single())
        assertEquals(0, session.currentCount("Stock Up"))
    }

    @Test
    fun deckRowWinsWhilePoolDiamondsAreStillAnimating() {
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount("Stock Up", 1)),
            draftPool = listOf(DeckCardCount("Stock Up", 2)),
        )
        session.observe(pipImage(2, drafted = 2), screen(total = 41, rowCount = 2))

        val exact = session.observe(
            pipImage(2, drafted = 2),
            screen(total = 40, rowCount = 1),
        )

        assertEquals(DeckGuidanceStatus.MATCHES, exact.status)
        assertTrue(exact.marks.isEmpty())
        assertEquals(1, session.currentCount("Stock Up"))
    }

    @Test
    fun partialOcrFrameRetainsTrustedCountWithoutBecomingUnable() {
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount("Stock Up", 1)),
            draftPool = listOf(DeckCardCount("Stock Up", 2)),
        )
        session.observe(pipImage(2, drafted = 2), screen(total = 41, rowCount = 2))

        val partial = session.observe(
            pipImage(1, drafted = 2),
            screen(total = 40, rowCount = null, poolVisible = false),
        )

        assertTrue(partial.status != DeckGuidanceStatus.UNABLE)
        assertEquals(2, session.currentCount("Stock Up"))

        val recovered = session.observe(pipImage(1, drafted = 2), screen(total = 40, rowCount = 1))
        assertEquals(DeckGuidanceStatus.MATCHES, recovered.status)
        assertEquals(1, session.currentCount("Stock Up"))
    }

    @Test
    fun unknownOcrNoiseDoesNotOverwriteTheKnownPool() {
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount("Stock Up", 1)),
            draftPool = listOf(DeckCardCount("Stock Up", 1)),
        )
        val noisy = screen(total = 40, rowCount = 1).copy(
            deckRows = screen(total = 40, rowCount = 1).deckRows + VisibleDeckRowObservation(
                1, "Unreadable OCR", NormalizedRect(0.785, 0.35, 0.20, 0.035),
                NormalizedRect(0.79, 0.35, 0.10, 0.02), 0.5,
            ),
        )

        assertEquals(DeckGuidanceStatus.MATCHES, session.observe(pipImage(1, drafted = 1), noisy).status)
    }

    @Test
    fun arenaTruncatedDeckRowStillProducesItsRedMark() {
        val canonicalName = "Black Widow, Double Agent"
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount(canonicalName, 0)),
            draftPool = listOf(DeckCardCount(canonicalName, 1)),
        )
        val truncatedRow = VisibleDeckRowObservation(
            count = 1,
            cardName = "Black Widow, Doubl...",
            bounds = rowBounds,
            textBounds = rowBounds,
            confidence = 0.95,
        )

        val model = session.observe(
            pipImage(current = 0, drafted = 0),
            screen(total = 41, rowCount = null, poolVisible = false).copy(deckRows = listOf(truncatedRow)),
        )

        val remove = assertIs<DeckGuidanceVisualMark.RemoveRow>(model.marks.single())
        assertEquals(rowBounds, remove.bounds)
        assertEquals(1, remove.currentCount)
    }

    @Test
    fun changingTheConfirmedBuildKeepsTheObservedArenaCounts() {
        val session = DeckGuidanceSession(
            target = listOf(DeckCardCount("Stock Up", 3)),
            draftPool = listOf(DeckCardCount("Stock Up", 4)),
        )
        assertEquals(
            DeckGuidanceStatus.MATCHES,
            session.observe(pipImage(3), screen(total = 40, rowCount = 3)).status,
        )

        session.updateTarget(listOf(DeckCardCount("Stock Up", 2)))
        val changed = session.observe(pipImage(3), screen(total = 40, rowCount = 3))

        val remove = assertIs<DeckGuidanceVisualMark.RemoveRow>(changed.marks.single())
        assertEquals(3, remove.currentCount)
        assertEquals(3, session.currentCount("Stock Up"))
    }

    @Test
    fun onlyTheFortyCardLimitedBuilderIsEligibleForGuidance() {
        assertTrue(isSupportedLimitedDeckBuilder(screen(total = 40, rowCount = 1)))
        assertTrue(!isSupportedLimitedDeckBuilder(screen(total = 60, rowCount = 1).copy(minimumDeckSize = 60)))
    }

    private fun screen(
        total: Int,
        rowCount: Int?,
        poolVisible: Boolean = true,
    ): DeckBuilderScreenObservation = DeckBuilderScreenObservation(
        deckSize = total,
        minimumDeckSize = 40,
        deckRows = rowCount?.let {
            listOf(VisibleDeckRowObservation(it, "Stock Up", rowBounds, rowBounds, 0.95))
        }.orEmpty(),
        poolCardTitles = if (poolVisible) listOf(poolTitle) else emptyList(),
        deckPanelBounds = NormalizedRect(0.78, 0.12, 0.21, 0.82),
        confidence = 0.95,
    )

    private fun pipImage(
        current: Int,
        drafted: Int = 4,
    ): BufferedImage = BufferedImage(2560, 1496, BufferedImage.TYPE_INT_RGB).also { image ->
        val graphics = image.createGraphics()
        graphics.color = Color(8, 8, 8)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
        val referenceAspect = 2560.0 / 1496.0
        val aspect = image.width.toDouble() / image.height
        val horizontalScale = min(1.0, referenceAspect / aspect)
        val verticalScale = min(1.0, aspect / referenceAspect)
        val pitch = 0.0147 * horizontalScale * image.width
        val runCenter = ((0.033 + 0.0585 * horizontalScale) * image.width)
        repeat(drafted) { index ->
            val x = (runCenter + (index - (drafted - 1) / 2.0) * pitch).roundToInt()
            val y = ((0.279 - 0.0370 * verticalScale) * image.height).roundToInt()
            val radius = (pitch * 0.32).roundToInt().coerceAtLeast(5)
            val xs = intArrayOf(x, x + radius, x, x - radius)
            val ys = intArrayOf(y - radius, y, y + radius, y)
            val diamond = image.createGraphics()
            diamond.color = Color(236, 236, 244)
            if (index < current) {
                diamond.fillPolygon(xs, ys, 4)
            } else {
                diamond.stroke = BasicStroke(2f)
                diamond.drawPolygon(xs, ys, 4)
            }
            diamond.dispose()
        }
    }
}
