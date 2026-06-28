package com.firstpick.overlay

import com.firstpick.core.Log
import java.io.File

/** On-screen bounds of a window, in global display points (top-left origin). */
data class WindowBounds(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Locates the MTG Arena window on macOS so the overlay can align to it.
 *
 * Uses a bundled CoreGraphics helper ([RESOURCE], built from native/macos/window-locator.swift):
 * it reads only the window owner name + bounds, which needs no Screen Recording or
 * Accessibility permission. Returns null off macOS, if Arena isn't on screen, or on any error.
 */
class WindowLocator(
    private val appName: String = "MTGA",
    private val helper: File? = extractHelper(),
) {
    fun locate(): WindowBounds? {
        val bin = helper ?: return null
        return runCatching {
            val proc = ProcessBuilder(bin.absolutePath, appName).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            parse(out)
        }.getOrElse { Log.warn(TAG, "locate failed: $it"); null }
    }

    companion object {
        private const val TAG = "WindowLocator"
        private const val RESOURCE = "/native/macos/window-locator"

        /** Parse the helper's one-line JSON. Internal so it's unit-testable without the binary. */
        internal fun parse(json: String): WindowBounds? {
            if (!json.contains("\"found\":true")) return null
            fun int(k: String) = Regex("\"$k\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()
            val x = int("x") ?: return null
            val y = int("y") ?: return null
            val w = int("w") ?: return null
            val h = int("h") ?: return null
            return WindowBounds(x, y, w, h)
        }

        /** Copy the bundled helper out of the classpath to an executable temp file (mac only). */
        private fun extractHelper(): File? {
            if (!System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) return null
            return runCatching {
                val stream = WindowLocator::class.java.getResourceAsStream(RESOURCE) ?: return null
                val tmp = File.createTempFile("window-locator", "").apply { deleteOnExit() }
                stream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.setExecutable(true)
                tmp
            }.getOrElse { Log.warn(TAG, "helper extract failed: $it"); null }
        }
    }
}
