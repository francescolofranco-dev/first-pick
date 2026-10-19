package com.firstpick.cards

/** Conservative recognition of mana-access effects, without card- or set-specific names. */
internal object ManaAccess {
    data class Cycling(val colors: Set<Char>, val cost: String)

    fun landcycling(text: String): Cycling? {
        val match = Regex("(?im)^(basic land|plains|island|swamp|mountain|forest)cycling ((?:\\{[^}]+})+)")
            .find(text) ?: return null
        val colors = when (match.groupValues[1].lowercase()) {
            "plains" -> setOf('W')
            "island" -> setOf('U')
            "swamp" -> setOf('B')
            "mountain" -> setOf('R')
            "forest" -> setOf('G')
            else -> "WUBRG".toSet()
        }
        return Cycling(colors, match.groupValues[2])
    }

    /** Credit a single unconditional resolution; no repeated combat/activated triggers. */
    fun cardsSeen(text: String): Int {
        fun number(value: String): Int = when (value) {
            "a", "one" -> 1
            "two" -> 2
            "three" -> 3
            "four" -> 4
            "five" -> 5
            "six" -> 6
            "seven" -> 7
            else -> value.toIntOrNull() ?: 0
        }
        return text.lowercase().lines().filter { line ->
            val unconditional = line.startsWith("draw ") || line.startsWith("discard ") || line.startsWith("scry ") || line.startsWith("surveil ") ||
                (line.startsWith("when ") && " enters," in line)
            unconditional && "if " !in line && "may " !in line && ":" !in line
        }.sumOf { line ->
            val draw = Regex("draw (a|one|two|three|four|five|six|seven|[0-9]+) cards?")
                .findAll(line).sumOf { number(it.groupValues[1]) }
            val filter = Regex("(?:scry|surveil) ([0-9]+)").findAll(line)
                .maxOfOrNull { number(it.groupValues[1]) } ?: 0
            maxOf(draw, filter) // Avoid counting a scried card again when it is drawn.
        }.coerceAtMost(4)
    }

    fun costValue(cost: String): Int? {
        val symbols = Regex("\\{([^}]+)}").findAll(cost).map { it.groupValues[1] }.toList()
        if (symbols.isEmpty() || symbols.any { it !in listOf("W", "U", "B", "R", "G") && it.toIntOrNull() == null }) return null
        return symbols.sumOf { it.toIntOrNull() ?: 1 }
    }

    fun payable(cost: String, baseColors: Set<Char>): Boolean = costValue(cost) != null &&
        Regex("\\{([^}]+)}").findAll(cost).all { symbol ->
            symbol.groupValues[1].toIntOrNull() != null || symbol.groupValues[1].singleOrNull() in baseColors
        }

    fun conditionalFixing(text: String): Boolean = text.lowercase().lines().any { line ->
        val manaEffect = "treasure" in line || "heartwood" in line || "add " in line || "search your library" in line
        manaEffect && ("if " in line || line.startsWith("whenever ") ||
            (line.startsWith("when ") && " dies" in line) || "combat damage" in line)
    }
}
