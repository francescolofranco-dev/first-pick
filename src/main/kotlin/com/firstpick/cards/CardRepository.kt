package com.firstpick.cards

import java.time.Instant

data class RankedCard(
    val grpId: Int,
    val name: String,
    val rating: CardRating?,
    val basicLandIdentity: BasicLandIdentity? = null,
) {
    val gihWr: Double? get() = rating?.gihWr
    val displayName: String get() = if (name.isNotBlank()) name else "Unknown #$grpId"
    val imageUrl: String? get() = rating?.imageUrl?.takeIf(String::isNotBlank) ?: basicLandIdentity?.imageUrl
    val isBasicLand: Boolean get() = basicLandIdentity != null ||
        rating?.types.orEmpty().any { it.contains("Basic Land", ignoreCase = true) } ||
        isBasicLandName(name)
}

data class RatingsDatasetInfo(
    val key: String,
    val source: RatingsDataSource,
    val lastUpdated: Instant,
    val cardCount: Int,
    val reliableCardCount: Int,
    val medianGamesPerCard: Int,
    val fallbackReason: FetchFailure? = null,
)

class CardRepository(
    private val client: SeventeenLandsClient = SeventeenLandsClient(),
    private val nameResolver: (Int) -> String? = { null },
    private val basicLandResolver: BasicLandResolver = ArenaBasicLandResolver(),
) {
    private var byMtgaId: Map<Int, CardRating> = emptyMap()
    private var byName: Map<String, CardRating> = emptyMap()

    var loadedKey: String? = null
        private set

    var setMetrics: SetMetrics = SetMetrics.EMPTY
        private set

    var ratingsInfo: RatingsDatasetInfo? = null
        private set

    val isLoaded: Boolean get() = loadedKey != null

    val cardNames: List<String> get() = byName.values.map { it.name }
    val cardRatings: List<CardRating> get() = byName.values.toList()

    suspend fun load(
        setCode: String,
        format: String,
        forceRefresh: Boolean = false,
    ): RatingsDatasetInfo {
        val key = "${setCode.uppercase()}_$format"
        if (!forceRefresh && key == loadedKey) ratingsInfo?.let { return it }

        val result = client.fetchWithMetadata(
            set = setCode,
            format = format,
            forceRefresh = forceRefresh,
        )
        index(result.ratings, key)
        return RatingsDatasetInfo(
            key = key,
            source = result.metadata.source,
            lastUpdated = result.metadata.lastUpdated,
            cardCount = result.ratings.size,
            reliableCardCount = result.ratings.count(CardRating::hasReliableWinRate),
            medianGamesPerCard = medianGamesPerCard(result.ratings),
            fallbackReason = result.metadata.fallbackReason,
        ).also { ratingsInfo = it }
    }

    fun isLoadedFor(setCode: String, format: String): Boolean =
        loadedKey == "${setCode.uppercase()}_$format"

    internal fun index(ratings: List<CardRating>, key: String = "manual") {
        byMtgaId = ratings.mapNotNull { r -> r.mtgaId?.let { it to r } }.toMap()
        byName = ratings.associateBy { normalize(it.name) }
        setMetrics = SetMetrics.from(ratings)
        loadedKey = key
    }

    fun resolvePack(grpIds: List<Int>): List<RankedCard> = grpIds.map(::resolve)

    fun rankPack(grpIds: List<Int>): List<RankedCard> =
        grpIds.map(::resolve).sortedWith(BEST_FIRST)

    fun resolve(grpId: Int): RankedCard {
        val byId = byMtgaId[grpId]
        if (byId != null) return RankedCard(grpId, byId.name, byId)
        val basic = basicLandResolver.resolve(grpId)
        if (basic != null) return RankedCard(grpId, basic.name, null, basic)
        val name = nameResolver(grpId)
        val byNm = name?.let { byName[normalize(it)] }
        return RankedCard(grpId, name ?: byNm?.name ?: "", byNm)
    }

    fun resolveName(name: String): RankedCard {
        val r = byName[normalize(name)]
        return RankedCard(r?.mtgaId ?: name.hashCode(), r?.name ?: name, r)
    }

    private fun normalize(name: String): String =
        name.lowercase().substringBefore(" //").trim()

    private fun medianGamesPerCard(ratings: List<CardRating>): Int {
        val samples = ratings.map(CardRating::everDrawnGameCount).filter { it > 0 }.sorted()
        if (samples.isEmpty()) return 0
        val middle = samples.size / 2
        return if (samples.size % 2 == 1) {
            samples[middle]
        } else {
            ((samples[middle - 1].toLong() + samples[middle]) / 2L).toInt()
        }
    }

    companion object {
        private val BEST_FIRST: Comparator<RankedCard> = compareByDescending<RankedCard> {
            it.rating?.takeIf { r -> r.hasReliableWinRate }?.gihWr ?: Double.NEGATIVE_INFINITY
        }.thenByDescending { it.gihWr ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.displayName }
    }
}
