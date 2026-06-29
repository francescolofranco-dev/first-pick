package com.firstpick.overlay

import com.firstpick.core.Log
import java.io.File

/** On-screen bounds of a window, in global display points (top-left origin). */
data class WindowBounds(val x: Int, val y: Int, val w: Int, val h: Int, val frontmost: Boolean = false)

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
            val frontmost = json.contains("\"frontmost\":true")
            return WindowBounds(x, y, w, h, frontmost)
        }

        private fun extractHelper(): File? = NativeHelpers.extract(RESOURCE, "window-locator")
    }
}
