package com.firstpick.diagnostics

import com.firstpick.overlay.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.hypot

@Serializable
internal data class ReplayCandidate(val name: String, val distance: Double)

@Serializable
internal data class ReplayFrameResult(
    val frame: Int,
    val atMs: Long,
    val completedAtMs: Long,
    val context: String,
    val event: String,
    val imageSha256: String? = null,
    val geometrySource: String? = null,
    val observations: Int = 0,
    val rawAssignment: Map<Int, String> = emptyMap(),
    val titleAnchors: Map<Int, String> = emptyMap(),
    val displayedCards: Map<Int, String> = emptyMap(),
    val displayedRatings: Map<Int, Double?> = emptyMap(),
    val status: String,
    val visible: Boolean,
    val knownCounts: Map<String, Int?> = emptyMap(),
    val marks: List<String> = emptyList(),
    val annotatedCards: Int = 0,
    val correctCards: Int = 0,
    val wrongCards: Int = 0,
    val missingCards: Int = 0,
    val countChecks: Int = 0,
    val wrongCounts: Int = 0,
    val unknownCounts: Int = 0,
    val statusMismatch: Boolean? = null,
    val recognitionToRenderMaxOffsetPx: Double? = null,
    val processingMs: Double,
    val candidateDistances: Map<Int, List<ReplayCandidate>> = emptyMap(),
    val pixelWidth: Int? = null,
    val pixelHeight: Int? = null,
)

internal class OverlayReplay(private val root: Path, private val recording: OverlayRecording) {
    private data class DraftState(
        val settler: RecognitionSettler = RecognitionSettler(3),
        var assignment: Map<Int, Int>? = null,
        var calibration: PackGridCalibration? = null,
    )

    fun run(): List<ReplayFrameResult> {
        recording.validate()
        val contexts = recording.contexts.associateBy { it.id }
        val drafts = mutableMapOf<String, DraftState>()
        val decks = mutableMapOf<String, DeckGuidanceSession>()
        val presentations = mutableMapOf<String, DeckOverlayPresentationState>()
        val references = contexts.values.filter { it.mode == "draft" }.associate { context ->
            context.id to context.cards.map { card ->
                card.reference?.let { path ->
                    val image = readRecordingImage(root, path)
                    val crop = card.crop?.let { image.getSubimage(it.x, it.y, it.width, it.height) } ?: image
                    CardRecognizer.ofCard(crop, card.isRoom)
                }
            }
        }
        var activeContext: String? = null
        return recording.frames.mapIndexed { index, frame ->
            val started = System.nanoTime()
            val context = contexts.getValue(frame.context)
            if (activeContext != frame.context) {
                // Context IDs represent actual pack/session entries, not merely identical card lists.
                drafts.clear()
                decks.clear()
                presentations.clear()
                activeContext = frame.context
            }
            val image = frame.image?.let { readRecordingImage(root, it) }
            val hash = frame.image?.let { name ->
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(recordingAsset(root, name)))
                    .joinToString("") { "%02x".format(it) }
            }
            val text = image?.let { ScreenTextFrame(it.width, it.height, frame.text.map { line -> line.observation() }) }
            var raw = emptyMap<Int, String>()
            var anchors = emptyMap<Int, String>()
            var displayed = emptyMap<Int, String>()
            var ratings = emptyMap<Int, Double?>()
            var counts = emptyMap<String, Int?>()
            var marks = emptyList<String>()
            var geometry: String? = null
            var offset: Double? = null
            var candidates = emptyMap<Int, List<ReplayCandidate>>()
            val status: String
            val visible: Boolean
            if (context.mode == "draft") {
                val state = drafts.getOrPut(context.id) { DraftState(calibration = context.calibration) }
                if (frame.event == "frame" && state.assignment == null) {
                    requireNotNull(image)
                    val refs = references.getValue(context.id)
                    val grid = if (frame.recognitionRects == null) CardDetector.detect(image, context.cards.size) else null
                    val fresh = if (context.cards.size >= 10) grid?.let(PackGeometry::fromGrid)?.takeIf(PackGeometry::isPlausible) else null
                    val rects = frame.recognitionRects?.map(RecordedRect::rect) ?: grid?.cards(context.cards.size)
                        ?: PackGeometry.rects(state.calibration ?: PackGeometry.DEFAULT, image.width, image.height, context.cards.size)
                    geometry = when { frame.recognitionRects != null -> "annotated crops (component input)"; grid != null -> "detected"; else -> "fallback calibration" }
                    val match = CardRecognizer.matchDetailed(image, rects, refs)
                    raw = match.assignment.mapValues { context.cards[it.value].name }
                    candidates = rects.associate { rect ->
                        val signature = CardRecognizer.ofRegion(image, rect.x, rect.y, rect.w, rect.h)
                        rect.index to refs.mapIndexedNotNull { i, ref ->
                            ref?.let { ReplayCandidate(context.cards[i].name, CardRecognizer.distance(signature, it)) }
                        }.sortedBy { it.distance }.take(3)
                    }
                    val title = if (frame.textAvailable && match.assignment.size >= refs.count { it != null } && state.settler.fullRecognitions == 2) {
                        PackCardTitleMatcher.anchors(requireNotNull(text), rects, context.cards.map { it.name })
                    } else emptyMap()
                    anchors = title.mapValues { context.cards[it.value].name }
                    if (refs.any { it != null } && match.assignment.size >= refs.count { it != null }) {
                        val constrained = if (title.isEmpty()) match else CardRecognizer.matchDetailed(image, rects, refs, title)
                        val settled = state.settler.observe(RecognitionAttempt(constrained, fresh, title))
                        if (settled != null) {
                            if (settled.calibration != null) state.calibration = settled.calibration
                            state.assignment = PackCardTitleMatcher.applyAnchors(settled.match.assignment, title)
                            val rendered = PackGeometry.rects(state.calibration ?: PackGeometry.DEFAULT, image.width, image.height, context.cards.size).associateBy { it.index }
                            offset = rects.maxOfOrNull { r ->
                                val p = rendered.getValue(r.index)
                                hypot((r.x + r.w / 2.0) - (p.x + p.w / 2.0), (r.y + r.h / 2.0) - (p.y + p.h / 2.0))
                            }
                        }
                    }
                }
                visible = frame.event !in setOf("focus_lost", "outside")
                if (visible) {
                    displayed = state.assignment.orEmpty().mapValues { context.cards[it.value].name }
                    ratings = state.assignment.orEmpty().mapValues { context.cards[it.value].value }
                }
                status = if (state.assignment != null) "ASSIGNED" else "READING"
            } else {
                val session = decks.getOrPut(context.id) { DeckGuidanceSession(context.target.map { it.count() }, context.cards.map { it.count() }) }
                var presentation = presentations.getOrPut(context.id) { DeckOverlayPresentationState.initial() }
                if (frame.event == "focus_lost") {
                    session.invalidate()
                    presentation = DeckOverlayPresentationState.initial().copy(contentVisible = false)
                } else if (frame.event == "tick") {
                    // Explicit watchdog event supplied by the trace, never inferred from replay speed.
                    presentation = presentation.copy(model = DeckGuidanceOverlayModel(DeckGuidanceStatus.READING))
                } else {
                    val screen = text?.takeIf { frame.textAvailable }?.let(DeckBuilderScreenInterpreter::interpret)
                    val shell = text?.takeIf { frame.textAvailable }?.let(DeckBuilderScreenInterpreter::detectShell)
                    val attempt = when {
                        frame.event == "reader_failure" -> DeckOverlayFrameAttempt.ReaderFailure
                        frame.event == "outside" -> DeckOverlayFrameAttempt.OutsideBuilder
                        screen != null && isSupportedLimitedDeckBuilder(screen) -> DeckOverlayFrameAttempt.Recognized(session.observe(requireNotNull(image), screen))
                        shell?.minimumDeckSize == 40 -> DeckOverlayFrameAttempt.SupportedBuilderShell
                        shell != null -> DeckOverlayFrameAttempt.UnsupportedLayout
                        else -> DeckOverlayFrameAttempt.OutsideBuilder
                    }
                    if (attempt !is DeckOverlayFrameAttempt.Recognized) session.unreadableFrame(frame.completedAtMs)
                    presentation = DeckOverlayPresentationReducer.reduce(presentation, attempt, frame.completedAtMs).state
                    if (!presentation.contentVisible) session.invalidate()
                }
                presentations[context.id] = presentation
                status = presentation.model.status.name
                visible = presentation.contentVisible && frame.window?.frontmost != false
                counts = context.cards.associate { it.name to session.currentCount(it.name) }
                marks = (if (visible) presentation.model.renderableMarks() else emptyList()).map { mark ->
                    when (mark) {
                        is DeckGuidanceVisualMark.AddCard -> "ADD ${mark.bounds}"
                        is DeckGuidanceVisualMark.RemoveRow -> "REMOVE ${mark.currentCount} ${mark.bounds}"
                    }
                }
            }
            val correct = frame.expectedCards.count { (slot, name) -> displayed[slot] == name }
            val wrong = frame.expectedCards.count { (slot, name) -> displayed[slot] != null && displayed[slot] != name }
            ReplayFrameResult(
                index, frame.atMs, frame.completedAtMs, context.id, frame.event, hash, geometry,
                frame.text.size, raw, anchors, displayed, ratings, status, visible, counts, marks,
                frame.expectedCards.size, correct, wrong, frame.expectedCards.size - correct - wrong,
                frame.expectedCounts.size,
                frame.expectedCounts.count { (name, count) -> counts[name] != null && counts[name] != count },
                frame.expectedCounts.count { (name, _) -> counts[name] == null },
                frame.expectedStatus?.let { it != status }, offset,
                (System.nanoTime() - started) / 1_000_000.0, candidates, image?.width, image?.height,
            )
        }
    }
}

internal fun replayReport(recording: OverlayRecording, results: List<ReplayFrameResult>, engineVersion: String = "unspecified", revision: String = "unspecified"): String {
    val annotated = results.sumOf { it.annotatedCards }
    val correct = results.sumOf { it.correctCards }
    val wrong = results.sumOf { it.wrongCards }
    val missing = results.sumOf { it.missingCards }
    fun percent(n: Int, d: Int) = if (d == 0) "unmeasured" else "%.1f%%".format(java.util.Locale.ROOT, n * 100.0 / d)
    return buildString {
        appendLine("# Overlay baseline replay")
        appendLine()
        appendLine("${recording.description}\n")
        appendLine("Provenance: ${recording.provenance}. Recorded version: ${recording.appVersion}.")
        appendLine("Replay engine: $engineVersion; source: $revision. JVM: ${System.getProperty("java.version")}; OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.")
        appendLine("Complete: ${recording.complete}; stop reason: ${recording.stopReason ?: "normal completion"}.\n")
        appendLine("| Measure | Result |\n| --- | ---: |")
        appendLine("| Observations | ${results.size} |")
        appendLine("| Unique input images | ${results.mapNotNull { it.imageSha256 }.distinct().size} |")
        appendLine("| Annotated card observations | $annotated |")
        appendLine("| Correct / wrong / missing | $correct / $wrong / $missing |")
        appendLine("| Identity precision on annotated displayed slots | ${percent(correct, correct + wrong)} |")
        appendLine("| Correct coverage on annotated slots | ${percent(correct, annotated)} |")
        appendLine("| Count checks / wrong / unknown | ${results.sumOf { it.countChecks }} / ${results.sumOf { it.wrongCounts }} / ${results.sumOf { it.unknownCounts }} |")
        appendLine("| Status checks / mismatches | ${results.count { it.statusMismatch != null }} / ${results.count { it.statusMismatch == true }} |\n")
        appendLine("## Limits\n")
        appendLine("This is an offline replay of recognition, settling, and deck guidance, not a recording of the actual Compose display. It does not measure click-through, window focus, end-to-end live latency, or perceived flicker.")
        appendLine("Repeated image hashes are repeated inputs, not independent recognition evidence. Coverage includes acquisition frames unless annotations exclude them. Unannotated results have no accuracy score.")
        appendLine("Annotated recognition crops exercise components and bypass grid detection. Recognition-to-render offset is a diagnostic comparison, not measured positioning error or jitter against ground truth.")
        appendLine("Capture/completion times preserve input chronology. Replay processing time measures offline work, not live response time. Late overlapping reads and actual display events need a future instrumented production trace.\n")
        appendLine("## Observation trace\n")
        appendLine("| Frame | Context | Event | Status | Correct / wrong / missing | Count wrong / unknown |\n| ---: | --- | --- | --- | --- | --- |")
        results.forEach { appendLine("| ${it.frame} | ${it.context} | ${it.event} | ${it.status} | ${it.correctCards} / ${it.wrongCards} / ${it.missingCards} | ${it.wrongCounts} / ${it.unknownCounts} |") }
    }
}

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: overlayReplay <manifest.json> <new-report-directory>" }
    val manifest = Path.of(args[0]).toAbsolutePath()
    val recording = loadRecording(manifest)
    val results = OverlayReplay(manifest.parent, recording).run()
    val output = Files.createDirectory(Path.of(args[1]).toAbsolutePath())
    Files.writeString(output.resolve("trace.json"), recordingJson.encodeToString(results))
    val engineVersion = OverlayReplay::class.java.getResourceAsStream("/app-version.txt")?.bufferedReader()?.use { it.readText().trim() } ?: "unknown"
    fun git(vararg arguments: String): Pair<Int, String> = runCatching {
        val process = ProcessBuilder(listOf("git") + arguments).redirectError(java.lang.ProcessBuilder.Redirect.DISCARD).start()
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.waitFor() to value
    }.getOrDefault(-1 to "")
    val head = git("rev-parse", "HEAD")
    val revision = if (head.first == 0) "${head.second} (dirty=${git("status", "--porcelain").second.isNotEmpty()})" else "unavailable"
    Files.writeString(output.resolve("report.md"), replayReport(recording, results, engineVersion, revision))
    println("Replay report: ${output.resolve("report.md")}")
}
