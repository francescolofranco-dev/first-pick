package com.firstpick.cards

data class RankedCard(
    val grpId: Int,
    val name: String,
    val rating: CardRating?,
) {
    val gihWr: Double? get() = rating?.gihWr
    val displayName: String get() = if (name.isNotBlank()) name else "Unknown #$grpId"
}

class CardRepository(
    private val client: SeventeenLandsClient = SeventeenLandsClient(),
    private val nameResolver: (Int) -> String? = { null },
) {
    private var byMtgaId: Map<Int, CardRating> = emptyMap()
    private var byName: Map<String, CardRating> = emptyMap()

    var loadedKey: String? = null
        private set

    var setMetrics: SetMetrics = SetMetrics.EMPTY
        private set

    val isLoaded: Boolean get() = loadedKey != null

    val cardNames: List<String> get() = byName.values.map { it.name }

    suspend fun load(setCode: String, format: String) {
        val key = "${setCode.uppercase()}_$format"
        if (key == loadedKey) return
        index(client.fetch(setCode, format), key)
    }

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

    companion object {
        private val BEST_FIRST: Comparator<RankedCard> = compareByDescending<RankedCard> {
            it.rating?.takeIf { r -> r.hasReliableWinRate }?.gihWr ?: Double.NEGATIVE_INFINITY
        }.thenByDescending { it.gihWr ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.displayName }
    }
}
