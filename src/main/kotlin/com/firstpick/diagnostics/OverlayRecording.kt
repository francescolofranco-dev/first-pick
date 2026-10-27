package com.firstpick.diagnostics

import com.firstpick.overlay.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

internal val recordingJson = Json { prettyPrint = true; ignoreUnknownKeys = false }

@Serializable
internal data class RecordingCard(
    val name: String,
    val reference: String? = null,
    val crop: RecordedRect? = null,
    val isRoom: Boolean = false,
    val count: Int = 1,
    val isBasicLand: Boolean = false,
    val value: Double? = null,
) {
    fun count() = DeckCardCount(name, count, isBasicLand)
}

@Serializable
internal data class RecordedRect(val index: Int = 0, val x: Int, val y: Int, val width: Int, val height: Int) {
    fun rect() = CardDetector.CardRect(index, x, y, width, height)
}

@Serializable
internal data class RecordedText(
    val text: String, val confidence: Double,
    val x: Double, val y: Double, val width: Double, val height: Double,
) {
    fun observation() = ScreenTextObservation(text, confidence, NormalizedRect(x, y, width, height))
    companion object {
        fun from(text: ScreenTextObservation) = RecordedText(
            text.text, text.confidence, text.bounds.x, text.bounds.y, text.bounds.width, text.bounds.height,
        )
    }
}

@Serializable
internal data class RecordedWindow(val x: Int, val y: Int, val width: Int, val height: Int, val frontmost: Boolean)

@Serializable
internal data class RecordedFrame(
    val atMs: Long,
    val completedAtMs: Long = atMs,
    val context: String,
    val image: String? = null,
    val event: String = "frame",
    val text: List<RecordedText> = emptyList(),
    val textAvailable: Boolean = false,
    val recognitionRects: List<RecordedRect>? = null,
    val expectedCards: Map<Int, String> = emptyMap(),
    val expectedCounts: Map<String, Int> = emptyMap(),
    val expectedStatus: String? = null,
    val action: String? = null,
    val window: RecordedWindow? = null,
)

@Serializable
internal data class RecordingContext(
    val id: String,
    val mode: String,
    val cards: List<RecordingCard>,
    val target: List<RecordingCard> = emptyList(),
    val calibration: PackGridCalibration? = null,
)

@Serializable
internal data class OverlayRecording(
    val schema: Int = 1,
    val description: String,
    val provenance: String,
    val appVersion: String,
    val contexts: List<RecordingContext>,
    val frames: List<RecordedFrame> = emptyList(),
    val complete: Boolean = true,
    val stopReason: String? = null,
) {
    fun validate() {
        require(schema == 1) { "Unsupported recording schema $schema" }
        require(contexts.isNotEmpty() && contexts.map { it.id }.distinct().size == contexts.size)
        contexts.forEach {
            require(it.id.isNotBlank() && it.mode in setOf("draft", "deck"))
            require(it.cards.isNotEmpty() && it.cards.size <= 250)
            require(it.cards.all { card -> card.name.isNotBlank() && card.count >= 0 })
        }
        val ids = contexts.map { it.id }.toSet()
        var lastCompletion = -1L
        frames.forEach {
            require(it.context in ids) { "Unknown context ${it.context}" }
            require(it.atMs >= 0 && it.completedAtMs >= it.atMs && it.completedAtMs >= lastCompletion) {
                "Frames must be in completion order with valid capture timestamps"
            }
            require(it.event in setOf("frame", "reader_failure", "focus_lost", "outside", "tick"))
            require(it.event != "frame" || it.image != null)
            require(it.recognitionRects == null || it.recognitionRects.map { rect -> rect.index }.distinct().size == it.recognitionRects.size)
            lastCompletion = it.completedAtMs
        }
    }
}

/** All assets travel with the recording. No URLs, live logs, caches, or parent paths. */
internal fun recordingAsset(root: Path, name: String): Path {
    val relative = Path.of(name)
    require(!relative.isAbsolute && relative.none { it.toString() == ".." }) { "Asset must stay inside the recording: $name" }
    val path = root.resolve(relative).normalize()
    require(path.toRealPath().startsWith(root.toRealPath())) { "Asset symlink escapes recording: $name" }
    return path
}

internal fun loadRecording(path: Path): OverlayRecording =
    recordingJson.decodeFromString<OverlayRecording>(Files.readString(path)).also { it.validate() }

internal fun writeRecording(path: Path, recording: OverlayRecording) {
    val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
    Files.writeString(temporary, recordingJson.encodeToString(recording))
    Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
}

internal fun readRecordingImage(root: Path, name: String) =
    requireNotNull(com.firstpick.ui.CardImageLoader.decodeBufferedImage(Files.readAllBytes(recordingAsset(root, name)))) {
        "Unreadable image $name"
    }
