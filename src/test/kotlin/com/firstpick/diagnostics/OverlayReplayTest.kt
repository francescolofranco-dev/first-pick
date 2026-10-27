package com.firstpick.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OverlayReplayTest {
    private fun corpus(): Path = Path.of(requireNotNull(javaClass.getResource("/overlay/baseline.json")).toURI())

    @Test
    fun replaysRealComponentFixturesWithoutLogsNetworkOrNativeCapture() {
        val file = corpus()
        val recording = loadRecording(file)
        val results = OverlayReplay(file.parent, recording).run()
        assertEquals(14, results.size)
        assertEquals(10, results.sumOf { it.correctCards })
        assertEquals(0, results.sumOf { it.wrongCards + it.missingCards })
        assertEquals(6, results.mapNotNull { it.imageSha256 }.distinct().size)
        assertEquals(2, results.count { it.geometrySource == "detected" })
        assertEquals("READING", results.first().status)
        assertEquals("ASSIGNED", results[2].status)
        assertTrue(results.take(3).all { it.imageSha256 == results[0].imageSha256 })
        val report = replayReport(recording, results)
        assertTrue("not a recording of the actual Compose display" in report)
        assertTrue("not independent" in report)
        assertTrue("unmeasured" in replayReport(recording, emptyList()))
    }

    @Test
    fun separatesWrongMappingsFromSuppressedCards() {
        val file = corpus()
        val original = loadRecording(file)
        val context = original.contexts.first()
        val acquisition = original.frames.first().copy(expectedCards = mapOf(0 to "Repulsor Blast"))
        val settled = original.frames[2].copy(expectedCards = mapOf(0 to "Wrong identity", 1 to "Avengers Hangar"))
        val recording = original.copy(contexts = listOf(context), frames = listOf(acquisition, original.frames[1], settled))
        val results = OverlayReplay(file.parent, recording).run()
        assertEquals(1, results.first().missingCards)
        assertEquals(1, results.last().wrongCards)
        assertEquals(1, results.last().correctCards)
        assertEquals(0, results.last().missingCards)
    }

    @Test
    fun deckTimelineKeepsUnknownCountsDistinctAndResetsOnFocusLoss() {
        val file = corpus()
        val context = RecordingContext("deck", "deck", listOf(RecordingCard("Negate"), RecordingCard("Abrade", count = 2)))
        fun line(t: String, x: Double, y: Double) = RecordedText(t, 0.95, x, y, 0.12, 0.018)
        val observed = RecordedFrame(0, context = "deck", image = "p1p1-13cards.png", textAvailable = true,
            text = listOf(line("40/40 Cards", 0.82, 0.13), line("Done", 0.86, 0.91),
                line("1x Negate", 0.765, 0.25), line("2x Abrade", 0.765, 0.29)),
            expectedCounts = mapOf("Negate" to 1, "Abrade" to 2), expectedStatus = "MATCHES")
        val recording = OverlayRecording(description = "Synthetic OCR timeline", provenance = "unit test", appVersion = "test",
            contexts = listOf(context.copy(target = context.cards)), frames = listOf(observed,
                RecordedFrame(100, context = "deck", event = "reader_failure"),
                RecordedFrame(200, context = "deck", event = "focus_lost", expectedCounts = mapOf("Negate" to 1))))
        val results = OverlayReplay(file.parent, recording).run()
        assertEquals(1, results.first().knownCounts["Negate"])
        assertEquals(0, results.first().wrongCounts)
        assertEquals(false, results.first().statusMismatch)
        assertEquals("READING", results[1].status)
        assertTrue(results[1].marks.isEmpty())
        assertEquals(1, results.last().unknownCounts)
        assertFalse(results.last().visible)
    }

    @Test
    fun readableBackgroundFramesKeepTheScanButHideInstructionsAndDetectEdits() {
        val file = corpus()
        val context = RecordingContext("deck", "deck", listOf(RecordingCard("Negate"), RecordingCard("Abrade", count = 2)),
            target = listOf(RecordingCard("Negate"), RecordingCard("Abrade")))
        fun line(t: String, y: Double) = RecordedText(t, 0.95, 0.765, y, 0.20, 0.018)
        fun frame(at: Long, total: Int, frontmost: Boolean, vararg rows: String) = RecordedFrame(
            at, context = "deck", image = "p1p1-13cards.png", textAvailable = true,
            window = RecordedWindow(0, 0, 1280, 748, frontmost),
            text = listOf(RecordedText("$total/40 Cards", 0.95, 0.82, 0.13, 0.12, 0.018),
                RecordedText("Done", 0.95, 0.86, 0.91, 0.12, 0.018)) +
                rows.mapIndexed { index, row -> line(row, 0.25 + index * 0.04) },
        )
        val recording = OverlayRecording(description = "Deck focus lifecycle", provenance = "synthetic OCR unit test", appVersion = "test",
            contexts = listOf(context), frames = listOf(
                frame(0, 40, true, "1x Negate", "2x Abrade"),
                frame(1_000, 40, false, "2x Abrade", "37x Island"),
                frame(2_000, 40, true, "2x Abrade", "37x Island"),
                frame(3_000, 39, false, "2x Abrade", "37x Island"),
                frame(4_000, 39, true, "2x Abrade", "37x Island"),
            ))
        val result = OverlayReplay(file.parent, recording).run()
        assertTrue(result.all { it.status == "NEEDS_CHANGES" })
        assertFalse(result[1].visible)
        assertTrue(result[1].marks.isEmpty())
        assertEquals(1, result[2].knownCounts["Negate"])
        assertTrue(result[2].visible)
        assertTrue(result[2].marks.any { it.startsWith("REMOVE") })
        assertEquals(0, result[3].knownCounts["Negate"])
        assertFalse(result[3].visible)
        assertTrue(result[3].marks.isEmpty())
        assertEquals(0, result[4].knownCounts["Negate"])
    }

    @Test
    fun rejectsInvalidContextsTimestampsAndEscapingAssets() {
        val root = Files.createTempDirectory("overlay-recording")
        try {
            Files.writeString(root.resolve("asset.txt"), "test")
            assertFailsWith<IllegalArgumentException> { recordingAsset(root, "../secret") }
            assertFailsWith<IllegalArgumentException> { recordingAsset(root, root.resolve("asset.txt").toString()) }
            val base = loadRecording(corpus())
            assertFailsWith<IllegalArgumentException> { base.copy(schema = 99).validate() }
            assertFailsWith<IllegalArgumentException> { base.copy(frames = listOf(base.frames[0].copy(completedAtMs = -1))).validate() }
            assertFailsWith<IllegalArgumentException> { base.copy(frames = listOf(base.frames[0].copy(context = "unknown"))).validate() }
            assertFailsWith<IllegalArgumentException> { base.copy(frames = listOf(base.frames[1], base.frames[0])).validate() }
            val partial = base.copy(complete = false, stopReason = "interrupted")
            writeRecording(root.resolve("manifest.json"), partial)
            assertEquals(partial, loadRecording(root.resolve("manifest.json")))
        } finally { root.toFile().deleteRecursively() }
    }
}
