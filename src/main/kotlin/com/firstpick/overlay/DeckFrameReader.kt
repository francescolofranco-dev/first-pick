package com.firstpick.overlay

import com.firstpick.core.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

internal data class DeckReaderFrame(val image: BufferedImage, val text: ScreenTextFrame)

/** A bounded, restartable native worker, shared by successive deck-builder frames. */
internal class DeckFrameReader(
    private val helper: File? = NativeHelpers.extract("/native/macos/deck-reader", "deck-reader"),
    private val timeoutMs: Long = 8_000,
) : AutoCloseable {
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "deck-reader-response").apply { isDaemon = true }
    }

    @Synchronized
    fun read(names: List<String>, windowWidth: Int = 0, windowHeight: Int = 0): DeckReaderFrame? {
        val binary = helper ?: return null
        val imageFile = File.createTempFile("firstpick-deck", ".bmp")
        return try {
            if (process?.isAlive != true) {
                stopWorker()
                process = ProcessBuilder(binary.absolutePath).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                input = process!!.outputStream.bufferedWriter()
                output = process!!.inputStream.bufferedReader()
            }
            input!!.apply {
                write(buildJsonObject {
                    put("path", imageFile.absolutePath)
                    put("windowWidth", windowWidth)
                    put("windowHeight", windowHeight)
                    put("words", JsonArray(names.map(::JsonPrimitive)))
                }.toString())
                newLine()
                flush()
            }
            val reader = requireNotNull(output)
            val pending = executor.submit<String?> { reader.readLine() }
            val raw = try { pending.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (error: Exception) {
                stopWorker()
                pending.cancel(true)
                throw error
            } ?: return null.also { stopWorker() }
            val text = VisionOcrPayloadParser.parse(raw) ?: return null
            val image = ImageIO.read(imageFile) ?: return null
            if (image.width != text.pixelWidth || image.height != text.pixelHeight) return null
            DeckReaderFrame(image, text)
        } catch (error: Exception) {
            Log.debug("DeckReader", "native reader unavailable: ${error.javaClass.simpleName}")
            stopWorker()
            null
        } finally {
            imageFile.delete()
        }
    }

    private fun stopWorker() {
        process?.let {
            it.destroy()
            if (!it.waitFor(150, TimeUnit.MILLISECONDS)) {
                it.destroyForcibly()
                it.waitFor(500, TimeUnit.MILLISECONDS)
            }
            runCatching { it.outputStream.close() }
            runCatching { it.inputStream.close() }
        }
        input = null
        output = null
        process = null
    }

    @Synchronized
    override fun close() {
        stopWorker()
        executor.shutdownNow()
    }
}
