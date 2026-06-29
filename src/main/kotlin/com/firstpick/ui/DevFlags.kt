package com.firstpick.ui

internal object DevFlags {
    val demoEnabled: Boolean =
        System.getenv("FIRSTPICK_DEMO") == "1" || System.getProperty("firstpick.demo") == "true"

    val overlayTrack: Boolean =
        System.getenv("FIRSTPICK_OVERLAY_TRACK") == "1" || System.getProperty("firstpick.overlayTrack") == "true"
}
