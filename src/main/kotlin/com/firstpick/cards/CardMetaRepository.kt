package com.firstpick.cards

class CardMetaRepository(
    private val client: ScryfallClient = ScryfallClient(),
) {
    private var byName: Map<String, CardMeta> = emptyMap()

    var loadedSet: String? = null
        private set

    suspend fun load(setCode: String, names: Collection<String> = emptyList()) {
        val key = setCode.uppercase()
        if (key == loadedSet) return
        val loaded = client.setMeta(key, names)
        if (loaded.isNotEmpty()) {
            byName = loaded
            loadedSet = key
        }
    }

    internal fun index(metas: List<CardMeta>, setCode: String = "TEST") {
        byName = metas.associateBy { normalize(it.name) }
        loadedSet = setCode.uppercase()
    }

    fun meta(name: String): CardMeta? = byName[normalize(name)]

    private fun normalize(name: String): String = name.lowercase().substringBefore(" //").trim()
}
