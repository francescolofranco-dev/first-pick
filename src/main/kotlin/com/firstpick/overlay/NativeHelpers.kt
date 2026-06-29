package com.firstpick.overlay

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import java.io.File

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
            if (!target.exists() || target.length() != bytes.size.toLong()) {
                target.writeBytes(bytes)
            }
            target.setExecutable(true)
            target
        }.getOrElse { Log.warn(TAG, "extract $name failed: $it"); null }
    }
}
