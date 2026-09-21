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
    internal val knownBasicCounts: Map<String, Int>,
    val failure: DeckObservationFailure?,
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

    /** Guidance can start after edits or a scroll; unseen cards have unknown counts. */
    fun initialState(): DeckObservationState = DeckObservationState(
        expectedNames = baselineCounts.keys,
        knownCounts = emptyMap(),
        totalCards = null,
        knownBasicCounts = emptyMap(),
        failure = null,
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

        // A total change not explained by observed cards may be an off-screen edit.
        // Never attribute it to a vanished row: scrolling and basic lands can do that too.
        val basics = frame.visibleRows.filter { it.isBasicLand || isKnownBasicLandName(it.name) }
            .associate { it.name to it.count }
        if (basics.values.any { it <= 0 }) return state.failed(DeckObservationFailure(DeckObservationFailureKind.INVALID_COUNT))
        val basicDelta = basics.entries.sumOf { (name, count) -> state.knownBasicCounts[name]?.let { count - it } ?: 0 }
        val visibleDelta = basicDelta + exact.counts.entries.sumOf { (name, count) ->
            state.knownCounts[name]?.let { count - it } ?: 0
        }
        val unexplainedChange = state.totalCards?.let { frame.totalCards - it != visibleDelta } ?: false
        val nextCounts = if (unexplainedChange) linkedMapOf() else LinkedHashMap(state.knownCounts)
        nextCounts.putAll(exact.counts)
        val nextBasics = if (unexplainedChange) linkedMapOf() else LinkedHashMap(state.knownBasicCounts)
        nextBasics.putAll(basics)
        val accountedTotal = nextCounts.values.sum() + nextBasics.values.sum()
        if (accountedTotal > frame.totalCards) {
            return state.failed(DeckObservationFailure(DeckObservationFailureKind.INVALID_TOTAL))
        }
        // Only complete accounting proves unseen drafted cards are absent.
        if (accountedTotal == frame.totalCards) {
            state.expectedNames.forEach { nextCounts.putIfAbsent(it, 0) }
        }
        return DeckObservationState(
            knownBasicCounts = nextBasics,
            expectedNames = state.expectedNames,
            knownCounts = nextCounts.toSortedMap(String.CASE_INSENSITIVE_ORDER),
            totalCards = frame.totalCards,
            failure = null,
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
