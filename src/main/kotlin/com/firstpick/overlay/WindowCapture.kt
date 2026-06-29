package com.firstpick.overlay

import com.firstpick.core.Log
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class WindowCapture(
    private val appName: String = "MTGA",
    private val helper: File? = extractHelper(),
) {
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

        private fun extractHelper(): File? = NativeHelpers.extract(RESOURCE, "window-capture")
    }
}
