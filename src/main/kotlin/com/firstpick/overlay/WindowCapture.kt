package com.firstpick.overlay

import com.firstpick.core.Log
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Captures the MTG Arena window to an image via a bundled ScreenCaptureKit helper
 * ([RESOURCE], built from native/macos/window-capture.swift) so the overlay can detect where
 * the cards actually are ([CardDetector]).
 *
 * Unlike [WindowLocator], this needs Screen Recording permission (granted to the app, or to the
 * terminal during dev). Returns null off macOS, without permission, if Arena isn't on screen,
 * or on any error — callers fall back to a geometric guess.
 *
 * The captured frame is in physical pixels (Retina = 2x the window's points), so detected
 * coordinates must be scaled by windowPoints / capturePixels to land in the overlay.
 */
class WindowCapture(
    private val appName: String = "MTGA",
    private val helper: File? = extractHelper(),
) {
    /** Capture the current Arena frame, or null if it can't be captured. */
    fun capture(): BufferedImage? {
        val bin = helper ?: return null
        return runCatching {
            val out = File.createTempFile("mtga-cap", ".png").apply { deleteOnExit() }
            val proc = ProcessBuilder(bin.absolutePath, appName, out.absolutePath)
                .redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (!log.contains("\"captured\":true")) {
                Log.warn(TAG, "capture failed: $log")
                out.delete()
                return null
            }
            ImageIO.read(out).also { out.delete() }
        }.getOrElse { Log.warn(TAG, "capture failed: $it"); null }
    }

    companion object {
        private const val TAG = "WindowCapture"
        private const val RESOURCE = "/native/macos/window-capture"

        /** Copy the bundled helper out of the classpath to an executable temp file (mac only). */
        private fun extractHelper(): File? {
            if (!System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) return null
            return runCatching {
                val stream = WindowCapture::class.java.getResourceAsStream(RESOURCE) ?: return null
                val tmp = File.createTempFile("window-capture", "").apply { deleteOnExit() }
                stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.setExecutable(true)
                tmp
            }.getOrElse { Log.warn(TAG, "helper extract failed: $it"); null }
        }
    }
}
