package com.firstpick.core

import java.time.Instant

/**
 * Minimal dependency-free logger (stderr). The app has no logging framework and a
 * desktop tool doesn't need one; this just replaces silent `runCatching{}.getOrNull()`
 * swallowing in the network layer with a visible, level-gated trail.
 *
 * Threshold via `FIRSTPICK_LOG_LEVEL` (DEBUG|INFO|WARN|ERROR); defaults to INFO.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private val threshold: Level =
        runCatching { System.getenv("FIRSTPICK_LOG_LEVEL")?.uppercase()?.let { Level.valueOf(it) } }
            .getOrNull() ?: Level.INFO

    fun debug(tag: String, msg: String) = log(Level.DEBUG, tag, msg, null)
    fun info(tag: String, msg: String) = log(Level.INFO, tag, msg, null)
    fun warn(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun error(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    private fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        if (level.ordinal < threshold.ordinal) return
        System.err.println("[${Instant.now()}] ${level.name} $tag: $msg")
        if (t != null) System.err.println("    ${t::class.simpleName}: ${t.message}")
    }
}
