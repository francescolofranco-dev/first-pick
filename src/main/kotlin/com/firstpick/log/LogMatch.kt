package com.firstpick.log

/**
 * "Normalized fuzzy match" mirroring the Python tool's `detect_string`: survive
 * minor Arena renames by ignoring spaces, underscores, dots, and dashes and
 * comparing case-insensitively. Always try the cheap raw substring first.
 */
object LogMatch {

    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                ' ', '_', '.', '-' -> {} // drop separators
                else -> append(c.uppercaseChar())
            }
        }
    }

    fun contains(line: String, keyword: String): Boolean {
        if (line.contains(keyword)) return true
        return normalize(line).contains(normalize(keyword))
    }
}
