package com.firstpick.overlay

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import java.io.File

/**
 * Extracts the bundled macOS native helper binaries from the classpath to a stable on-disk
 * location so they can be exec'd from the JVM.
 *
 * Why a stable path (not a random temp file): the capture helper needs Screen Recording
 * permission, and macOS grants TCC permission to a specific binary. Extracting to a fixed
 * path under Application Support — and only rewriting it when the bundled helper actually
 * changes (size differs) — lets the user's grant persist across launches instead of
 * re-prompting every run. Returns null off macOS or if the resource isn't bundled.
 */
object NativeHelpers {
    private const val TAG = "NativeHelpers"

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    fun extract(resource: String, name: String): File? {
        if (!isMac) return null
        return runCatching {
            val bytes = NativeHelpers::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: return null
            val dir = AppPaths.appSupport.resolve("bin").toFile().apply { mkdirs() }
            val target = File(dir, name)
            // Rewrite only when the bundled helper differs, to preserve the path's TCC grant.
            if (!target.exists() || target.length() != bytes.size.toLong()) {
                target.writeBytes(bytes)
            }
            target.setExecutable(true)
            target
        }.getOrElse { Log.warn(TAG, "extract $name failed: $it"); null }
    }
}
