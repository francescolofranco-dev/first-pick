package com.firstpick.overlay

import com.firstpick.core.Log
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class WindowCapture(
    private val appName: String = "MTGA",
    private val helper: File? = extractHelper(),
) {
    @Synchronized
    fun capture(): BufferedImage? {
        val bin = helper ?: return null
        return runCatching {
            val out = File.createTempFile("mtga-cap", ".png").apply { deleteOnExit() }
            val proc = ProcessBuilder(bin.absolutePath, appName, out.absolutePath)
                .redirectErrorStream(true).start()
            if (!proc.waitFor(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroy()
                if (!proc.waitFor(PROCESS_KILL_GRACE_MS, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
                out.delete()
                Log.warn(TAG, "capture timed out")
                return@runCatching null
            }
            val log = proc.inputStream.bufferedReader().readText().trim()
            if (!log.contains("\"captured\":true")) {
                Log.warn(TAG, "capture failed: $log")
                out.delete()
                return@runCatching null
            }
            ImageIO.read(out).also { out.delete() }
        }.getOrElse { Log.warn(TAG, "capture failed: $it"); null }
    }

    companion object {
        private const val TAG = "WindowCapture"
        private const val RESOURCE = "/native/macos/window-capture"
        private const val CAPTURE_TIMEOUT_MS = 3_000L
        private const val PROCESS_KILL_GRACE_MS = 250L

        private fun extractHelper(): File? = NativeHelpers.extract(RESOURCE, "window-capture")
    }
}
