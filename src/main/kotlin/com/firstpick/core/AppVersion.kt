package com.firstpick.core

object AppVersion {
    val value: String = checkNotNull(AppVersion::class.java.getResourceAsStream("/app-version.txt")) {
        "Missing packaged app version"
    }.bufferedReader().use { it.readText().trim() }
}
