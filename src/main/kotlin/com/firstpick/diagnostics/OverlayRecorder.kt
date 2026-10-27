package com.firstpick.diagnostics

import com.firstpick.overlay.DeckFrameReader
import com.firstpick.overlay.VisionOcr
import com.firstpick.overlay.WindowCapture
import com.firstpick.overlay.WindowLocator
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Explicit developer command. Never started by the normal app or by replay. */
fun recordMain(args: Array<String>) {
    require(args.size in 2..4) { "Usage: overlayRecord <context.json> <new-directory> [frames=60] [intervalMs=300]" }
    val contextFile = Path.of(args[0]).toAbsolutePath()
    val template = loadRecording(contextFile)
    require(template.contexts.size == 1 && template.frames.isEmpty()) { "Use one context with an empty frames list" }
    val limit = args.getOrNull(2)?.toInt() ?: 60
    val interval = args.getOrNull(3)?.toLong() ?: 300L
    require(limit in 1..300 && interval in 100..5_000)
    val output = Files.createDirectory(Path.of(args[1]).toAbsolutePath())
    var bytesWritten = 0L
    val budget = 250L * 1024 * 1024
    val context = template.contexts.single().let { original ->
        original.copy(cards = original.cards.mapIndexed { index, card ->
            card.reference?.let { name ->
                val source = recordingAsset(contextFile.parent, name)
                require(bytesWritten + Files.size(source) <= budget) { "Reference assets exceed recording budget" }
                val target = "reference-$index${source.fileName.toString().substringAfterLast('.', ".png").let { ".$it" }}"
                Files.copy(source, output.resolve(target))
                bytesWritten += Files.size(source)
                card.copy(reference = target)
            } ?: card
        })
    }
    val frames = mutableListOf<RecordedFrame>()
    val version = OverlayRecorderVersion.current()
    var recording = template.copy(contexts = listOf(context), frames = emptyList(), complete = false,
        appVersion = version, provenance = "explicit local Arena capture; ground truth not annotated", stopReason = "interrupted")
    val manifest = output.resolve("manifest.json")
    writeRecording(manifest, recording)
    println("Recording Arena only to $output; at most $limit observations, 120 seconds, 250 MiB. No logs or account data are read. Game UI in images may include player names. Ctrl-C stops; partial manifests remain replayable.")
    val start = System.nanoTime()
    fun elapsed() = (System.nanoTime() - start) / 1_000_000L
    val locator = WindowLocator()
    val capture = WindowCapture()
    val ocr = VisionOcr()
    val reader = if (context.mode == "deck") DeckFrameReader() else null
    var reason = "observation limit"
    try {
        for (index in 0 until limit) {
            if (elapsed() >= 120_000) { reason = "duration limit"; break }
            val at = elapsed()
            val bounds = locator.locate()
            val frame = if (bounds == null || (reader == null && !bounds.frontmost)) {
                RecordedFrame(at, elapsed(), context.id, event = "focus_lost")
            } else {
                val deck = reader?.read(context.cards.map { it.name }, bounds.w, bounds.h)
                val image = if (reader != null) deck?.image else capture.capture()
                val text = if (reader != null) deck?.text else image?.let(ocr::recognize)
                if (image == null) RecordedFrame(at, elapsed(), context.id, event = "reader_failure")
                else {
                    val completedAt = elapsed()
                    val data = ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
                    if (bytesWritten + data.size > budget) { reason = "byte limit"; break }
                    val name = "frame-${index.toString().padStart(4, '0')}.png"
                    Files.write(output.resolve(name), data)
                    bytesWritten += data.size
                    RecordedFrame(at, completedAt, context.id, name,
                        text = text?.observations.orEmpty().map(RecordedText::from), textAvailable = text != null)
                }
            }
            frames += frame.copy(window = bounds?.let { RecordedWindow(it.x, it.y, it.w, it.h, it.frontmost) })
            recording = recording.copy(frames = frames.toList())
            writeRecording(manifest, recording)
            val rest = interval - (elapsed() - at)
            if (rest > 0) Thread.sleep(rest)
        }
        recording = recording.copy(complete = true, stopReason = reason)
    } finally {
        reader?.close()
        writeRecording(manifest, recording)
    }
    println("Saved ${frames.size} observations (${bytesWritten / 1024} KiB): $manifest")
}

private object OverlayRecorderVersion {
    fun current(): String = javaClass.getResourceAsStream("/app-version.txt")?.bufferedReader()?.use { it.readText().trim() } ?: "unknown"
}
