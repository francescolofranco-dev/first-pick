package com.firstpick.overlay

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import java.io.File

object NativeHelpers {
    private const val TAG = "NativeHelpers"

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)

    internal fun packagedHelper(appPath: String?, name: String): File? {
        if (appPath.isNullOrBlank()) return null
        val directory = File(appPath).parentFile?.parentFile?.resolve("Helpers") ?: return null
        if (!directory.isDirectory) return null // Distribution/development resource fallback.
        return directory.resolve(name).also {
            check(it.isFile && it.canExecute()) { "Missing packaged helper: $name" }
        }
    }

    fun extract(resource: String, name: String): File? {
        if (!isMac) return null
        return runCatching {
            // Local production builds carry signed helpers inside their own bundle.
            // Avoid sharing mutable executables between installed app versions.
            packagedHelper(System.getProperty("jpackage.app-path"), name)?.let { return it }
            val bytes = NativeHelpers::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: return null
            val dir = AppPaths.appSupport.resolve("bin").toFile().apply { mkdirs() }
            val target = File(dir, name)
            if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
                val replacement = File.createTempFile("$name-", ".tmp", dir)
                try {
                    replacement.writeBytes(bytes)
                    replacement.setExecutable(true)
                    java.nio.file.Files.move(replacement.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { replacement.delete() }
            }
            target.setExecutable(true)
            target
        }.getOrElse { Log.warn(TAG, "extract $name failed: $it"); null }
    }
}
