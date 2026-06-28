package com.firstpick.ui

/**
 * Development-only feature flags, read once from the environment at startup.
 */
internal object DevFlags {
    /**
     * The built-in demo / simulation draft is a development aid, not a feature of the
     * shipped app. It's enabled when `FIRSTPICK_DEMO=1` (or `-Dfirstpick.demo=true`),
     * which `./gradlew run` sets automatically — the packaged `.dmg` leaves it off, so
     * end users only ever see the real "start a draft in Arena" flow.
     */
    val demoEnabled: Boolean =
        System.getenv("FIRSTPICK_DEMO") == "1" || System.getProperty("firstpick.demo") == "true"

    /**
     * Spike: pin a transparent, click-through frame to the live Arena window to prove the
     * overlay can align to and track the client. Enable with `FIRSTPICK_OVERLAY_TRACK=1`
     * or `./gradlew run -Ptrack`. Off in the shipped app until the feature lands.
     */
    val overlayTrack: Boolean =
        System.getenv("FIRSTPICK_OVERLAY_TRACK") == "1" || System.getProperty("firstpick.overlayTrack") == "true"
}
