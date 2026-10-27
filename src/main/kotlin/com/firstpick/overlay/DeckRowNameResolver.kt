package com.firstpick.overlay

/** Resolves deck-list OCR only; pool titles and draft artwork matching stay independent. */
internal class DeckRowNameResolver(private val baselineCounts: Map<String, Int>) {
    private val matcher = CanonicalCardNameMatcher(baselineCounts.keys)

    fun resolve(
        rows: List<VisibleDeckRowObservation>,
        currentPoolCounts: Map<String, Int> = emptyMap(),
    ): Map<VisibleDeckRowObservation, String> {
        val resolved = linkedMapOf<VisibleDeckRowObservation, String>()
        val ambiguous = linkedMapOf<VisibleDeckRowObservation, List<String>>()
        for (row in rows) {
            when (val match = match(row.cardName)) {
                is CanonicalCardNameMatch.Matched -> resolved[row] = match.name
                is CanonicalCardNameMatch.Ambiguous -> ambiguous[row] = match.candidates
                CanonicalCardNameMatch.NoMatch -> Unit
            }
        }
        // A distinct visible row cannot use copies already accounted for by another
        // row. Only exhausted draft-pool capacity excludes a candidate; target-build
        // preferences and remembered off-screen counts are never identity evidence.
        // A current pool read showing all copies outside the deck also rules a
        // candidate out. It cannot override a directly identified deck row.
        do {
            val used = resolved.entries.groupingBy { it.value }.fold(0) { count, entry -> count + entry.key.count }
            var progress = false
            for ((row, names) in ambiguous.toMap()) {
                val available = names.filter {
                    currentPoolCounts[it] != 0 && (baselineCounts[it] ?: 0) - (used[it] ?: 0) >= row.count
                }
                if (available.size == 1) {
                    resolved[row] = available.single()
                    ambiguous.remove(row)
                    progress = true
                    break // Recompute capacity before resolving another row.
                }
            }
        } while (progress)
        return resolved
    }

    private fun match(raw: String): CanonicalCardNameMatch {
        val exact = matcher.match(raw)
        if (exact !is CanonicalCardNameMatch.NoMatch) return exact
        // Vision sometimes joins a mana glyph to a shortened title: "Proft... (".
        // Preserve the explicit ellipsis and discard only a mana-shaped suffix.
        val truncated = TRUNCATED_WITH_MANA.matchEntire(raw.trim())
        if (truncated != null) return matcher.match(truncated.groupValues[1])
        // A full title can also include a joined symbol, e.g. "Ruric Thar, Biomagus Q".
        val withoutMana = raw.trim().replace(MANA_SUFFIX, "")
        return if (withoutMana != raw.trim()) matcher.match(withoutMana) else exact
    }

    private companion object {
        val TRUNCATED_WITH_MANA = Regex("""^(.+?(?:\.\.\.|…))\s+[0-9WUBRGCQOX/{}()\[\]<>\s]+$""")
        val MANA_SUFFIX = Regex("""\s+[0-9WUBRGCQOX/{}()\[\]<>]+$""")
    }
}
