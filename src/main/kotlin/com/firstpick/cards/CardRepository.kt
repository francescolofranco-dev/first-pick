package com.firstpick.cards

/** A pack card resolved against 17Lands data; [rating] is null if unmatched. */
data class RankedCard(
    val grpId: Int,
    val name: String,
    val rating: CardRating?,
) {
    val gihWr: Double? get() = rating?.gihWr
    val displayName: String get() = if (name.isNotBlank()) name else "Unknown #$grpId"
}

/**
 * Loads 17Lands ratings for a set/format and resolves pack grpIds against them.
 *
 * Resolution order: direct by `mtga_id`, then by name (a [nameResolver] backed by
 * the local MTGA card DB can be supplied later for zero-day / alt-art grpIds).
 */
class CardRepository(
    private val client: SeventeenLandsClient = SeventeenLandsClient(),
    /** grpId -> card name fallback (e.g. local MTGA SQLite). Optional. */
    private val nameResolver: (Int) -> String? = { null },
) {
    private var byMtgaId: Map<Int, CardRating> = emptyMap()
    private var byName: Map<String, CardRating> = emptyMap()

    var loadedKey: String? = null
        private set

    /** Set-wide GIH win-rate distribution for z-scoring (advisor input). */
    var setMetrics: SetMetrics = SetMetrics.EMPTY
        private set

    val isLoaded: Boolean get() = loadedKey != null

    /**
     * Every card name in the loaded set, per 17Lands. This is the authoritative draft
     * pool — including bonus-sheet cards (e.g. OTJ's *The Big Score*) that a Scryfall
     * `set:` query misses — so Scryfall metadata is fetched against this list, not the set.
     */
    val cardNames: List<String> get() = byName.values.map { it.name }

    /** Fetch + index ratings for a set/format. Idempotent per (set, format). */
    suspend fun load(setCode: String, format: String) {
        val key = "${setCode.uppercase()}_$format"
        if (key == loadedKey) return
        index(client.fetch(setCode, format), key)
    }

    /** Index a ratings list directly (used by [load]; exposed for tests). */
    internal fun index(ratings: List<CardRating>, key: String = "manual") {
        byMtgaId = ratings.mapNotNull { r -> r.mtgaId?.let { it to r } }.toMap()
        byName = ratings.associateBy { normalize(it.name) }
        setMetrics = SetMetrics.from(ratings)
        loadedKey = key
    }

    /** Resolve a pack to cards in pack order (no ranking — the advisor orders them). */
    fun resolvePack(grpIds: List<Int>): List<RankedCard> = grpIds.map(::resolve)

    /** Rank a pack (best first) purely by win rate. Used by the raw view / demo. */
    fun rankPack(grpIds: List<Int>): List<RankedCard> =
        grpIds.map(::resolve).sortedWith(BEST_FIRST)

    fun resolve(grpId: Int): RankedCard {
        val byId = byMtgaId[grpId]
        if (byId != null) return RankedCard(grpId, byId.name, byId)
        val name = nameResolver(grpId)
        val byNm = name?.let { byName[normalize(it)] }
        return RankedCard(grpId, name ?: byNm?.name ?: "", byNm)
    }

    /** Resolve a card by name (used by the eval harness, which works from 17Lands names). */
    fun resolveName(name: String): RankedCard {
        val r = byName[normalize(name)]
        return RankedCard(r?.mtgaId ?: name.hashCode(), r?.name ?: name, r)
    }

    private fun normalize(name: String): String =
        name.lowercase().substringBefore(" //").trim() // match split-card front face

    companion object {
        /** Reliable win rate desc; unrated cards last; stable by name. */
        private val BEST_FIRST: Comparator<RankedCard> = compareByDescending<RankedCard> {
            it.rating?.takeIf { r -> r.hasReliableWinRate }?.gihWr ?: Double.NEGATIVE_INFINITY
        }.thenByDescending { it.gihWr ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.displayName }
    }
}
