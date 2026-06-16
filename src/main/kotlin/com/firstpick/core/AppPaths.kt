package com.firstpick.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Standard macOS locations for config, cache, and the MTG Arena log.
 *
 * Data lives under ~/Library/Application Support/ (outside the project tree),
 * mirroring how the Python reference tool persists its state.
 */
object AppPaths {
    private val home: Path = Path.of(System.getProperty("user.home"))

    val appSupport: Path = home.resolve("Library/Application Support/FirstPick")
    val cacheDir: Path = appSupport.resolve("cache")
    val configFile: Path = appSupport.resolve("config.json")

    /** Default MTG Arena Unity log on macOS. */
    val defaultPlayerLog: Path =
        home.resolve("Library/Logs/Wizards of the Coast/MTGA/Player.log")

    fun ensureDirectories() {
        Files.createDirectories(appSupport)
        Files.createDirectories(cacheDir)
    }
}
