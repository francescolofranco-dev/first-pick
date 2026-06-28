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
}
