package com.firstpick.cards

/**
 * Caches per-set [CardMeta] (mana values etc.) from Scryfall. The advisor's curve
 * logic degrades gracefully — [meta] simply returns null — if a set hasn't loaded
 * or Scryfall is unreachable.
 */
class CardMetaRepository(
    private val client: ScryfallClient = ScryfallClient(),
) {
    private var byName: Map<String, CardMeta> = emptyMap()

    var loadedSet: String? = null
        private set

    suspend fun load(setCode: String) {
        val key = setCode.uppercase()
        if (key == loadedSet) return
        val loaded = client.setMeta(key)
        if (loaded.isNotEmpty()) {
            byName = loaded
            loadedSet = key
        }
    }

    /** Expose the lookup directly for tests / manual indexing. */
    internal fun index(metas: List<CardMeta>, setCode: String = "TEST") {
        byName = metas.associateBy { normalize(it.name) }
        loadedSet = setCode.uppercase()
    }

    fun meta(name: String): CardMeta? = byName[normalize(name)]

    private fun normalize(name: String): String = name.lowercase().substringBefore(" //").trim()
}
