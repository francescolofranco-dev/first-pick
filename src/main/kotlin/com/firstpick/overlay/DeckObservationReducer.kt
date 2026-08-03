package com.firstpick.overlay

/** An exact current main-deck copy count read from either visible Arena surface. */
data class VisibleExactCardCount(
    val name: String,
    val count: Int,
    val isBasicLand: Boolean = false,
)

data class DeckObservationFrame(
    val totalCards: Int,
    val visibleRows: List<VisibleExactCardCount> = emptyList(),
    val visiblePool: List<VisibleExactCardCount> = emptyList(),
)

enum class DeckObservationStatus { READING, READY, UNABLE }

enum class DeckObservationFailureKind {
    INVALID_TOTAL,
    INVALID_COUNT,
    UNKNOWN_NAME,
    AMBIGUOUS_NAME,
    CONFLICTING_COUNTS,
    COUNT_EXCEEDS_DRAFT_POOL,
    AMBIGUOUS_DISAPPEARANCE,
    BASELINE_MISMATCH,
}

data class DeckObservationFailure(
    val kind: DeckObservationFailureKind,
    val name: String? = null,
    val candidates: List<String> = emptyList(),
)

@ConsistentCopyVisibility
data class DeckObservationState internal constructor(
    val expectedNames: Set<String>,
    val knownCounts: Map<String, Int>,
    val totalCards: Int?,
    val failure: DeckObservationFailure?,
    internal val lastVisibleRows: Map<String, Int>,
    internal val lastVisibleCounts: Map<String, Int>,
) {
    val status: DeckObservationStatus
        get() = when {
            failure != null -> DeckObservationStatus.UNABLE
            totalCards != null && expectedNames.all(knownCounts::containsKey) -> DeckObservationStatus.READY
            else -> DeckObservationStatus.READING
        }

    fun countFor(name: String): Int? = knownCounts[name]

    fun asDeckReadState(): DeckReadState = when (status) {
        DeckObservationStatus.READING -> DeckReadState.Reading
        DeckObservationStatus.UNABLE -> DeckReadState.Unable
        DeckObservationStatus.READY -> DeckReadState.Ready(
            DeckSnapshot(
                cards = expectedNames.sortedWith(String.CASE_INSENSITIVE_ORDER).map { name ->
                    DeckCardCount(name, requireNotNull(knownCounts[name]))
                },
                totalCards = requireNotNull(totalCards),
            ),
        )
    }
}

class DeckObservationReducer(draftPoolBaseline: List<DeckCardCount>) {
    private val baselineCounts: Map<String, Int> = draftPoolBaseline.asSequence()
        .filterNot { it.isBasicLand || isKnownBasicLandName(it.name) }
        .filter { it.count > 0 }
        .groupingBy(DeckCardCount::name)
        .fold(0) { total, card -> total + card.count }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    private val matcher = CanonicalCardNameMatcher(baselineCounts.keys)

    /** Arena initially puts every drafted nonbasic into the main deck. */
    fun initialState(): DeckObservationState = DeckObservationState(
        expectedNames = baselineCounts.keys,
        knownCounts = baselineCounts,
        totalCards = null,
        failure = null,
        lastVisibleRows = emptyMap(),
        lastVisibleCounts = emptyMap(),
    )

    fun reduce(
        state: DeckObservationState,
        frame: DeckObservationFrame,
    ): DeckObservationState {
        if (state.expectedNames != baselineCounts.keys) {
            return state.failed(DeckObservationFailure(DeckObservationFailureKind.BASELINE_MISMATCH))
        }
        if (frame.totalCards < 0) {
            return state.failed(DeckObservationFailure(DeckObservationFailureKind.INVALID_TOTAL))
        }

        val rows = resolve(frame.visibleRows, allowZero = false)
        if (rows.failure != null) return state.failed(rows.failure)
        val pool = resolve(
            frame.visiblePool.filterNot { observation ->
                val match = matcher.match(observation.name)
                match is CanonicalCardNameMatch.Matched && match.name in rows.counts
            },
            allowZero = true,
        )
        if (pool.failure != null) return state.failed(pool.failure)
        val exact = merge(rows.counts, pool.counts)
        if (exact.failure != null) return state.failed(exact.failure)

        val inferredCounts = inferUniqueVanishedRow(state, frame.totalCards, rows.counts, pool.counts, exact.counts)

        val nextCounts = LinkedHashMap(state.knownCounts)
        nextCounts.putAll(exact.counts)
        nextCounts.putAll(inferredCounts)
        return DeckObservationState(
            expectedNames = state.expectedNames,
            knownCounts = nextCounts.toSortedMap(String.CASE_INSENSITIVE_ORDER),
            totalCards = frame.totalCards,
            failure = null,
            lastVisibleRows = rows.counts,
            lastVisibleCounts = exact.counts,
        )
    }

    private fun resolve(
        visible: List<VisibleExactCardCount>,
        allowZero: Boolean,
    ): CountResolution {
        val counts = LinkedHashMap<String, Int>()
        for (observation in visible) {
            if (observation.isBasicLand || isKnownBasicLandName(observation.name)) continue
            if (observation.count < 0 || (!allowZero && observation.count == 0)) {
                return CountResolution(failure = DeckObservationFailure(
                    DeckObservationFailureKind.INVALID_COUNT,
                    observation.name,
                ))
            }
            val canonical = when (val match = matcher.match(observation.name)) {
                is CanonicalCardNameMatch.Matched -> match.name
                CanonicalCardNameMatch.NoMatch -> return CountResolution(failure = DeckObservationFailure(
                    DeckObservationFailureKind.UNKNOWN_NAME,
                    observation.name,
                ))
                is CanonicalCardNameMatch.Ambiguous -> return CountResolution(failure = DeckObservationFailure(
                    DeckObservationFailureKind.AMBIGUOUS_NAME,
                    observation.name,
                    match.candidates,
                ))
            }
            if (observation.count > requireNotNull(baselineCounts[canonical])) {
                return CountResolution(failure = DeckObservationFailure(
                    DeckObservationFailureKind.COUNT_EXCEEDS_DRAFT_POOL,
                    canonical,
                ))
            }
            val previous = counts.putIfAbsent(canonical, observation.count)
            if (previous != null && previous != observation.count) {
                return CountResolution(failure = DeckObservationFailure(
                    DeckObservationFailureKind.CONFLICTING_COUNTS,
                    canonical,
                ))
            }
        }
        return CountResolution(counts.toSortedMap(String.CASE_INSENSITIVE_ORDER))
    }

    private fun merge(
        rows: Map<String, Int>,
        pool: Map<String, Int>,
    ): CountResolution {
        val merged = LinkedHashMap(rows)
        for ((name, count) in pool) {
            // A visible deck row is authoritative. Pool diamonds can lag one
            // animation frame, so a disagreement must never poison the deck.
            merged.putIfAbsent(name, count)
        }
        return CountResolution(merged.toSortedMap(String.CASE_INSENSITIVE_ORDER))
    }

    private fun inferUniqueVanishedRow(
        state: DeckObservationState,
        totalCards: Int,
        rows: Map<String, Int>,
        pool: Map<String, Int>,
        exact: Map<String, Int>,
    ): Map<String, Int> {
        val previousTotal = state.totalCards ?: return emptyMap()
        val totalDelta = totalCards - previousTotal
        if (totalDelta == 0) return emptyMap()

        val missingRows = state.lastVisibleRows.keys - rows.keys - pool.keys
        val vanished = missingRows.singleOrNull() ?: return emptyMap()
        val consecutiveVisibleDelta = exact.entries.sumOf { (name, count) ->
            state.lastVisibleCounts[name]?.let { count - it } ?: 0
        }
        val unexplainedDelta = totalDelta - consecutiveVisibleDelta
        if (unexplainedDelta == 0) return emptyMap()

        val retainedRowAnchors = state.lastVisibleRows.keys intersect rows.keys
        if (retainedRowAnchors.isEmpty()) return emptyMap()
        val previousCount = state.lastVisibleRows[vanished] ?: return emptyMap()
        val inferredCount = previousCount + unexplainedDelta
        val draftedCount = baselineCounts[vanished] ?: return emptyMap()
        if (inferredCount !in 0..draftedCount) return emptyMap()
        return mapOf(vanished to inferredCount)
    }

    private fun DeckObservationState.failed(failure: DeckObservationFailure): DeckObservationState =
        copy(failure = failure)

    private data class CountResolution(
        val counts: Map<String, Int> = emptyMap(),
        val failure: DeckObservationFailure? = null,
    )

    private companion object {
        val BASIC_LAND_MATCHER = CanonicalCardNameMatcher(
            listOf(
                "Plains",
                "Island",
                "Swamp",
                "Mountain",
                "Forest",
                "Wastes",
                "Snow-Covered Plains",
                "Snow-Covered Island",
                "Snow-Covered Swamp",
                "Snow-Covered Mountain",
                "Snow-Covered Forest",
            ),
        )

        fun isKnownBasicLandName(name: String): Boolean =
            BASIC_LAND_MATCHER.match(name) is CanonicalCardNameMatch.Matched
    }
}
