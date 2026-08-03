package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckBuilderScreenInterpreterTest {
    private fun text(
        value: String,
        x: Double,
        y: Double,
        width: Double = 0.12,
        height: Double = 0.018,
        confidence: Double = 0.95,
    ) = ScreenTextObservation(value, confidence, NormalizedRect(x, y, width, height))

    private fun frame(vararg observations: ScreenTextObservation) =
        ScreenTextFrame(2560, 1496, observations.toList())

    @Test
    fun recognizesVerticalDeckBuilderAndCombinedRows() {
        val result = DeckBuilderScreenInterpreter.interpret(
            frame(
                text("Sideboard", 0.83, 0.095),
                text("Ultimate Jeskai 59/60 Cards", 0.80, 0.135, width = 0.18),
                text("1x Three Steps Ahead", 0.765, 0.225, width = 0.17),
                text("2x Firebending Lesson", 0.765, 0.265, width = 0.18),
                text("3x Stock Up", 0.765, 0.305, width = 0.11),
                text("Done", 0.86, 0.91, width = 0.06, height = 0.03),
                text("Stock Up", 0.025, 0.270, width = 0.09),
                text("Stockman, Mad Fly-entist", 0.178, 0.270, width = 0.13),
                text("Crumb and Get It", 0.025, 0.642, width = 0.11),
            ),
        )

        assertNotNull(result)
        assertEquals(59, result.deckSize)
        assertEquals(60, result.minimumDeckSize)
        assertEquals(listOf(1, 2, 3), result.deckRows.map { it.count })
        assertEquals(listOf("Three Steps Ahead", "Firebending Lesson", "Stock Up"), result.deckRows.map { it.cardName })
        assertEquals(listOf("Stock Up", "Stockman, Mad Fly-entist", "Crumb and Get It"), result.poolCardTitles.map { it.cardName })
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), result.poolCardTitles.map { it.row to it.column })
        assertTrue(result.deckRows.all { it.bounds.isValid() && it.bounds.width > it.textBounds.width })
        assertTrue(result.confidence > 0.8)
    }

    @Test
    fun joinsSplitCountAndNameAndToleratesVisionReadingOneAsI() {
        val result = DeckBuilderScreenInterpreter.interpret(
            frame(
                text("Sideboard", 0.83, 0.09),
                text("40/40 Cards", 0.82, 0.13),
                text("Ix", 0.765, 0.25, width = 0.025),
                text("Negate", 0.800, 0.25, width = 0.07),
                text("2x", 0.765, 0.29, width = 0.025),
                text("Abrade", 0.800, 0.29, width = 0.07),
                text("1", 0.945, 0.29, width = 0.01),
                text("Done", 0.86, 0.91),
            ),
        )

        assertNotNull(result)
        assertEquals(listOf("Negate", "Abrade"), result.deckRows.map { it.cardName })
        assertEquals(listOf(1, 2), result.deckRows.map { it.count })
        assertTrue(result.deckRows.first().confidence < result.deckRows.last().confidence)
    }

    @Test
    fun matchesTheSeparateCountAndNameSegmentationProducedByVision() {
        val result = DeckBuilderScreenInterpreter.interpret(
            frame(
                text("Sideboard", 0.901, 0.112, width = 0.044, height = 0.015),
                text("60/60 Cards", 0.836, 0.166, width = 0.060, height = 0.015),
                text("4X", 0.791, 0.281, width = 0.016, height = 0.017),
                text("Tablet of Discovery", 0.818, 0.279, width = 0.109, height = 0.020),
                text("2x", 0.791, 0.321, width = 0.016, height = 0.022),
                text("Day of Judgment", 0.815, 0.321, width = 0.097, height = 0.025),
                text("3x", 0.791, 0.366, width = 0.016, height = 0.020),
                text("Inevitable Defeat", 0.815, 0.363, width = 0.102, height = 0.023),
                text("Done", 0.882, 0.923, width = 0.047, height = 0.030),
                text("Stock Up", 0.033, 0.279, width = 0.038, height = 0.015),
                text("Stockman, Mad Fly-entist 4(", 0.183, 0.279, width = 0.109, height = 0.012),
                text("Baxter Stockman", 0.333, 0.279, width = 0.065, height = 0.012),
            ),
        )

        assertNotNull(result)
        assertEquals(
            listOf("Tablet of Discovery", "Day of Judgment", "Inevitable Defeat"),
            result.deckRows.map { it.cardName },
        )
        assertEquals(listOf(4, 2, 3), result.deckRows.map { it.count })
        assertEquals(listOf("Stock Up", "Stockman, Mad Fly-entist", "Baxter Stockman"), result.poolCardTitles.map { it.cardName })
    }

    @Test
    fun calibratesRowOutlineToArenaChromeAtCapturedResolution() {
        val screenshotWidth = 2_554
        val screenshotHeight = 1_484
        val result = DeckBuilderScreenInterpreter.interpret(
            ScreenTextFrame(
                screenshotWidth,
                screenshotHeight,
                listOf(
                    text("43/40 Cards", 0.832807681, 0.162075331, width = 0.059675338, height = 0.018349338),
                    text("1x", 0.787790698, 0.215000000, width = 0.015988372, height = 0.017500000),
                    text("Go Nuts!", 0.813922921, 0.212287799, width = 0.052386715, height = 0.022924403),
                    text("1x", 0.787790697, 0.257500001, width = 0.014534884, height = 0.019999999),
                    text("Rapid Rescue", 0.813953489, 0.254716981, width = 0.078488373, height = 0.022911051),
                    text("1x", 0.787790698, 0.300000000, width = 0.014534884, height = 0.020000000),
                    text("Serpent Specialist", 0.813953493, 0.297169812, width = 0.104651156, height = 0.025606469),
                    text("1x", 0.789244186, 0.342500000, width = 0.013081395, height = 0.020000000),
                    text("Agents of HYDRA", 0.815406978, 0.340000000, width = 0.097383719, height = 0.025229111),
                    text("Done", 0.879360465, 0.925000000, width = 0.047965116, height = 0.030000000),
                ),
            ),
        )

        assertNotNull(result)
        val first = result.deckRows.first().bounds
        assertEquals(2_004.4, first.x * screenshotWidth, absoluteTolerance = 1.5)
        assertEquals(2_533.6, first.right * screenshotWidth, absoluteTolerance = 1.5)
        assertEquals(54.2, first.height * screenshotHeight, absoluteTolerance = 2.0)
        assertEquals(332.0, first.centerY * screenshotHeight, absoluteTolerance = 2.0)
        assertTrue(result.deckRows.all { row ->
            kotlin.math.abs(row.bounds.x - first.x) < 0.000_001 &&
                kotlin.math.abs(row.bounds.right - first.right) < 0.000_001 &&
                kotlin.math.abs(row.bounds.height - first.height) < 0.000_001
        })
    }

    @Test
    fun excludesRowsObscuredByDoneFade() {
        val result = DeckBuilderScreenInterpreter.interpret(
            frame(
                text("Sideboard", 0.83, 0.09),
                text("42/40 Cards", 0.82, 0.13),
                text("1x Island", 0.765, 0.78),
                text("1x Plains", 0.765, 0.82),
                text("1x Hidden Behind Button", 0.765, 0.89),
                text("Done", 0.86, 0.92, height = 0.03),
            ),
        )

        assertNotNull(result)
        assertEquals(42, result.deckSize)
        assertEquals(40, result.minimumDeckSize)
        assertEquals(listOf("Island", "Plains"), result.deckRows.map { it.cardName })
    }

    @Test
    fun detectsTheSupportedBuilderShellWhenEveryCardRowIsObscured() {
        val obscured = frame(
            text("Sideboard", 0.83, 0.09),
            text("Draft Deck 41/40 Cards", 0.80, 0.13, width = 0.18),
            text("Done", 0.86, 0.91, width = 0.06, height = 0.03),
            text("Hover Preview", 0.40, 0.35, width = 0.20, height = 0.08),
        )

        assertNull(DeckBuilderScreenInterpreter.interpret(obscured))
        val shell = DeckBuilderScreenInterpreter.detectShell(obscured)
        assertNotNull(shell)
        assertEquals(41, shell.deckSize)
        assertEquals(40, shell.minimumDeckSize)
        assertTrue(shell.confidence > 0.8)
    }

    @Test
    fun shellDetectionRequiresBothTheHeaderAndDoneButtonInTheDeckPanel() {
        val header = text("41/40 Cards", 0.82, 0.13)
        val done = text("Done", 0.86, 0.91)

        assertNull(DeckBuilderScreenInterpreter.detectShell(frame(header)))
        assertNull(DeckBuilderScreenInterpreter.detectShell(frame(done)))
        assertNull(
            DeckBuilderScreenInterpreter.detectShell(
                frame(header, text("Done", 0.20, 0.91)),
            ),
        )
        assertNull(
            DeckBuilderScreenInterpreter.detectShell(
                frame(
                    text("41/40 Cards", 0.82, 0.13, confidence = 0.20),
                    text("Done", 0.86, 0.91, confidence = 0.20),
                ),
            ),
        )
    }

    @Test
    fun failsClosedWithoutEachRequiredLayoutSignal() {
        val rows = arrayOf(
            text("1x Negate", 0.765, 0.25),
            text("2x Abrade", 0.765, 0.29),
        )

        assertNull(DeckBuilderScreenInterpreter.interpret(frame(text("Done", 0.86, 0.91), *rows)))
        assertNull(DeckBuilderScreenInterpreter.interpret(frame(text("40/40 Cards", 0.82, 0.13), *rows)))
        assertNull(
            DeckBuilderScreenInterpreter.interpret(
                frame(text("Sideboard", 0.83, 0.09), text("40/40 Cards", 0.82, 0.13), rows.first()),
            ),
        )
        assertNull(
            DeckBuilderScreenInterpreter.interpret(
                frame(
                    text("Sideboard", 0.20, 0.09),
                    text("40/40 Cards", 0.20, 0.13),
                    text("Done", 0.20, 0.91),
                    text("1x Negate", 0.20, 0.25),
                    text("2x Abrade", 0.20, 0.29),
                ),
            ),
        )
    }

    @Test
    fun rejectsInvalidInputGeometry() {
        val invalid = ScreenTextFrame(
            2560,
            1496,
            listOf(ScreenTextObservation("40/40 Cards", 0.9, NormalizedRect(0.9, 0.1, 0.2, 0.02))),
        )

        assertNull(DeckBuilderScreenInterpreter.interpret(invalid))
    }
}
