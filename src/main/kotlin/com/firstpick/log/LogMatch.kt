package com.firstpick.log

object LogMatch {

    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                ' ', '_', '.', '-' -> {}
                else -> append(c.uppercaseChar())
            }
        }
    }

    fun contains(line: String, keyword: String): Boolean {
        if (line.contains(keyword)) return true
        return normalize(line).contains(normalize(keyword))
    }
}
