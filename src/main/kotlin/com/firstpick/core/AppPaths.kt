package com.firstpick.core

import java.nio.file.Files
import java.nio.file.Path

object AppPaths {
    private val home: Path = Path.of(System.getProperty("user.home"))

    val appSupport: Path = home.resolve("Library/Application Support/FirstPick")
    val cacheDir: Path = appSupport.resolve("cache")
    val configFile: Path = appSupport.resolve("config.json")

    val defaultPlayerLog: Path =
        home.resolve("Library/Logs/Wizards of the Coast/MTGA/Player.log")

    val arenaRawDataDir: Path =
        home.resolve("Library/Application Support/com.wizards.mtga/Downloads/Raw")

    fun ensureDirectories() {
        Files.createDirectories(appSupport)
        Files.createDirectories(cacheDir)
    }
}
