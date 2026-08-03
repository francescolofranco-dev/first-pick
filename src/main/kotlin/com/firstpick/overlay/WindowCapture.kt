package com.firstpick.overlay

import com.firstpick.core.Log
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class WindowCapture(
    private val appName: String = "MTGA",
    private val helper: File? = extractHelper(),
    private val captureTimeoutMs: Long = CAPTURE_TIMEOUT_MS,
    private val killGraceMs: Long = PROCESS_KILL_GRACE_MS,
) {
    @Synchronized
    fun capture(): BufferedImage? {
        val bin = helper ?: return null
        return runCatching {
            val out = File.createTempFile("mtga-cap", ".png")
            var proc: Process? = null
            try {
                proc = ProcessBuilder(bin.absolutePath, appName, out.absolutePath)
                    .redirectErrorStream(true).start()
                if (!proc.waitFor(captureTimeoutMs, TimeUnit.MILLISECONDS)) {
                    terminate(proc)
                    Log.debug(TAG, "capture timed out")
                    return@runCatching null
                }
                val log = proc.inputStream.bufferedReader().readText().trim()
                if (!log.contains("\"captured\":true")) {
                    Log.debug(TAG, "capture failed: $log")
                    return@runCatching null
                }
                ImageIO.read(out)
            } finally {
                proc?.takeIf { it.isAlive }?.let(::terminate)
                out.delete()
            }
        }.getOrElse { Log.debug(TAG, "capture failed: $it"); null }
    }

    private fun terminate(proc: Process) {
        proc.destroy()
        if (proc.waitFor(killGraceMs, TimeUnit.MILLISECONDS)) return
        proc.destroyForcibly()
        if (!proc.waitFor(FORCE_KILL_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.warn(TAG, "capture helper ${proc.pid()} could not be reaped")
        }
    }

    companion object {
        private const val TAG = "WindowCapture"
        private const val RESOURCE = "/native/macos/window-capture"
        private const val CAPTURE_TIMEOUT_MS = 3_000L
        private const val PROCESS_KILL_GRACE_MS = 250L
        private const val FORCE_KILL_WAIT_MS = 1_000L

        private fun extractHelper(): File? = NativeHelpers.extract(RESOURCE, "window-capture")
    }
}
