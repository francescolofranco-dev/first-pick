package com.firstpick.guide

import com.firstpick.cards.*
import com.firstpick.core.AppPaths
import java.nio.file.Path

data class ThemeReference(
    val pair: String,
    val theme: String,
    val commons: List<GuideCard> = emptyList(),
    val uncommons: List<GuideCard> = emptyList(),
)

data class SetReference(
    val code: String,
    val name: String,
    val themes: List<ThemeReference>,
    val loadingCards: Boolean = false,
    val cardsLoaded: Boolean = false,
    val cardError: Boolean = false,
)

/** Browsing uses separate repositories so another set cannot overwrite the active draft. */
class SetReferenceRepository(
    private val cacheDir: Path = AppPaths.cacheDir,
    private val cardRepository: () -> CardRepository = { CardRepository() },
) {
    private val profiles = mutableMapOf<String, SetSynergyProfile>()

    suspend fun sets(): List<SetReference> = StandardSets.manifest.sets.map { set ->
        val code = set.code.uppercase()
        val repository = SynergyRepository(cacheDir)
        repository.load(code)
        val profile = repository.index?.profile
        if (profile != null) profiles[code] = profile
        SetReference(code, profile?.setName?.ifBlank { code } ?: code,
            profile?.archetypes.orEmpty().map { ThemeReference(it.pair, it.referenceTheme.ifBlank { it.name }) })
    }

    suspend fun cards(code: String): List<ThemeReference> {
        val profile = requireNotNull(profiles[code])
        val repository = cardRepository()
        repository.load(code, DraftRatingsFormats.DEFAULT)
        return buildThemeReferences(profile, repository.cardRatings)
    }
}

/** Only explicitly tagged cards in the pair compete; rare/mythic cards never fill uncommon slots. */
internal fun buildThemeReferences(profile: SetSynergyProfile, ratings: List<CardRating>): List<ThemeReference> {
    val byName = ratings.associateBy { SynergyIndex.normalize(it.name) }
    return profile.archetypes.map { archetype ->
        val candidates = (archetype.signposts + archetype.enablers + archetype.payoffs)
            .distinctBy(SynergyIndex::normalize)
            .mapNotNull { byName[SynergyIndex.normalize(it)] }
            .filter { card -> card.color.all { it in archetype.pair } }
            .sortedWith(compareByDescending<CardRating> { it.hasReliableWinRate }
                .thenByDescending { it.gihWr ?: Double.NEGATIVE_INFINITY }
                .thenByDescending { it.everDrawnGameCount })
        fun cards(rarity: String) = candidates.filter { it.rarity.equals(rarity, ignoreCase = true) }
            .take(3).map { card ->
                GuideCard(card.name, card.color.firstOrNull() ?: 'C', card.rarity,
                    card.gihWr, card.everDrawnGameCount, card.imageUrl.takeIf { it.isNotBlank() })
            }
        ThemeReference(archetype.pair, archetype.referenceTheme.ifBlank { archetype.name }, cards("common"), cards("uncommon"))
    }
}
