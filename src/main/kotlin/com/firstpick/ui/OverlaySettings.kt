package com.firstpick.ui

import com.firstpick.core.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.exists

/** Persisted overlay + app preferences, stored as JSON at [AppPaths.configFile]. */
@Serializable
data class OverlaySettings(
    val x: Int = 100,
    val y: Int = 100,
    val width: Int = 1000,
    val height: Int = 650,
    val gridLeft: Float = 0.072f,
    val gridTop: Float = 0.095f,
    val gridColGap: Float = 0.150f,
    val gridRowGap: Float = 0.275f,
    val isLocked: Boolean = false,
    /** Selected 17Lands data source ([RatingsFormat] choice); null = the default. */
    val ratingsFormatOverride: String? = null,
    /** Let mouse clicks pass through the drafting overlay to Arena beneath it. */
    val clickThrough: Boolean = true,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun load(): OverlaySettings {
            AppPaths.ensureDirectories()
            val file = AppPaths.configFile
            if (!file.exists()) return OverlaySettings()
            return runCatching {
                val content = Files.readString(file)
                json.decodeFromString<OverlaySettings>(content)
            }.getOrElse { OverlaySettings() }
        }

        fun save(settings: OverlaySettings) {
            runCatching {
                AppPaths.ensureDirectories()
                val content = json.encodeToString(serializer(), settings)
                Files.writeString(AppPaths.configFile, content)
            }
        }
    }
}
