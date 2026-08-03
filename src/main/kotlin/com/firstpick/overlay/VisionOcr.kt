package com.firstpick.overlay

import com.firstpick.core.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Runs the bundled macOS Vision helper against a captured window image.
 *
 * A null result means OCR was unavailable, timed out, or returned a payload that
 * did not pass validation. The deck-overlay presentation layer applies a bounded
 * grace period before discarding its last trusted reading.
 */
class VisionOcr(
    private val helper: File? = extractHelper(),
    private val timeoutMs: Long = OCR_TIMEOUT_MS,
    private val killGraceMs: Long = PROCESS_KILL_GRACE_MS,
) {
    @Synchronized
    fun recognize(image: BufferedImage): ScreenTextFrame? {
        val bin = helper ?: return null
        var input: File? = null
        var output: File? = null
        var process: Process? = null
        return try {
            val inputFile = File.createTempFile("firstpick-ocr-input", ".png").also { input = it }
            val outputFile = File.createTempFile("firstpick-ocr-output", ".json").also { output = it }
            if (!ImageIO.write(image, "png", inputFile)) return null
            process = ProcessBuilder(bin.absolutePath, inputFile.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                terminate(process)
                Log.debug(TAG, "Vision OCR timed out")
                null
            } else if (process.exitValue() != 0) {
                Log.debug(TAG, "Vision OCR failed with exit ${process.exitValue()}")
                null
            } else {
                VisionOcrPayloadParser.parse(outputFile.readText())
            }
        } catch (t: Throwable) {
            Log.debug(TAG, "Vision OCR failed: $t")
            null
        } finally {
            process?.takeIf { it.isAlive }?.let(::terminate)
            input?.delete()
            output?.delete()
        }
    }

    private fun terminate(process: Process) {
        process.destroy()
        if (process.waitFor(killGraceMs, TimeUnit.MILLISECONDS)) return
        process.destroyForcibly()
        process.waitFor(FORCE_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    companion object {
        private const val TAG = "VisionOcr"
        private const val RESOURCE = "/native/macos/vision-ocr"
        private const val OCR_TIMEOUT_MS = 8_000L
        private const val PROCESS_KILL_GRACE_MS = 250L
        private const val FORCE_KILL_WAIT_MS = 1_000L

        private fun extractHelper(): File? = NativeHelpers.extract(RESOURCE, "vision-ocr")
    }
}

internal object VisionOcrPayloadParser {
    private const val SCHEMA = 1
    private const val MAX_OUTPUT_CHARS = 2_000_000
    private const val MAX_OBSERVATIONS = 2_000
    private const val MAX_TEXT_CHARS = 512

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String): ScreenTextFrame? {
        if (raw.isBlank() || raw.length > MAX_OUTPUT_CHARS) return null
        val payload = runCatching { json.decodeFromString<WirePayload>(raw) }.getOrNull() ?: return null
        if (!payload.ok || payload.schema != SCHEMA) return null
        if (payload.width !in 1..100_000 || payload.height !in 1..100_000) return null
        if (payload.observations.size > MAX_OBSERVATIONS) return null

        val observations = ArrayList<ScreenTextObservation>(payload.observations.size)
        for (wire in payload.observations) {
            val text = wire.text.trim().replace(WHITESPACE, " ")
            val bounds = NormalizedRect(wire.x, wire.y, wire.width, wire.height)
            if (text.isEmpty() || text.length > MAX_TEXT_CHARS || text.any(Char::isISOControl)) return null
            if (!wire.confidence.isFinite() || wire.confidence !in 0.0..1.0 || !bounds.isValid()) return null
            observations += ScreenTextObservation(text, wire.confidence, bounds)
        }
        return ScreenTextFrame(payload.width, payload.height, observations)
    }

    @Serializable
    private data class WirePayload(
        val schema: Int = 0,
        val ok: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val observations: List<WireObservation> = emptyList(),
    )

    @Serializable
    private data class WireObservation(
        val text: String,
        val confidence: Double,
        val x: Double,
        val y: Double,
        @SerialName("w") val width: Double,
        @SerialName("h") val height: Double,
    )

    private val WHITESPACE = Regex("\\s+")
}
