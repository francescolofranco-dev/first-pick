package com.firstpick.overlay

import com.firstpick.core.Log
import java.io.File

data class WindowBounds(val x: Int, val y: Int, val w: Int, val h: Int, val frontmost: Boolean = false)

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
