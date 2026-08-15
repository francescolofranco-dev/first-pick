package com.firstpick.guide

import com.firstpick.advisor.COLOR_PAIRS
import com.firstpick.cards.CardRating
import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.cards.SynergyIndex
import com.firstpick.cards.SynergySourceKind

/** Numeric Limited fundamentals shared by the guide, pick advisor, and deck builder. */
object LimitedPolicy {
    const val DECK_SIZE = 40
    const val LAND_SLOTS = 17
    const val SPELL_SLOTS = DECK_SIZE - LAND_SLOTS
    const val MIN_BUILDABLE_SPELLS = 21
    const val FINAL_CREATURE_TARGET = 15
    const val FINAL_REMOVAL_TARGET = 4
    const val MAX_SPLASH_CARDS = 4
    const val MIN_BASE_COLOR_PIPS = 4
    const val MIN_BASE_COLOR_RATIO = 0.20

    const val DRAFT_TOTAL_PICKS = 45
    const val PICKS_PER_PACK = 15
    const val POOL_REMOVAL_TARGET = 6.0
    const val POOL_CREATURE_TARGET = 14.0
    const val POOL_TWO_DROP_TARGET = 8.0
    const val POOL_FIXING_TARGET = 3
    const val POOL_FINISHER_TARGET = 2.0
    const val POOL_TOP_END_LIMIT = 6

    const val COLOR_COMMITMENT_RAMP_START = 0.15
    const val DECK_NEEDS_RAMP_START = 0.25
    const val DECK_NEEDS_RAMP_SPAN = 0.55
    const val DECK_FIT_RAMP_START = 0.10
    const val DECK_FIT_RAMP_SPAN = 0.23
}

data class GuidePrinciple(
    val id: String,
    val title: String,
    val summary: String,
    val appliedByFirstPick: String,
    val sourceIds: List<String> = emptyList(),
)

data class GuideSource(
    val title: String,
    val publisher: String,
    val url: String,
    val kind: String = "Article",
    val id: String = "",
    val author: String = "",
    val date: String = "",
)

data class GuideCard(
    val name: String,
    val color: Char,
    val rarity: String,
    val gihWr: Double?,
    val games: Int,
    val imageUrl: String?,
)

data class GuideColorCards(
    val color: Char,
    val commons: List<GuideCard>,
    val nonCommons: List<GuideCard>,
    val sourceIds: List<String> = emptyList(),
)

data class GuideMechanic(
    val name: String,
    val summary: String,
    val sourceIds: List<String> = emptyList(),
)

data class GuideArchetype(
    val pair: String,
    val name: String,
    val speed: String,
    val plan: String,
    val winRate: Double?,
    val signposts: List<String>,
    val enablers: List<String>,
    val payoffs: List<String>,
    val keyCards: List<String>,
    val sourceIds: List<String> = emptyList(),
)

data class GuideCombo(
    val cards: List<String>,
    val note: String,
    val sourceIds: List<String> = emptyList(),
)

data class SetDraftGuide(
    val setCode: String,
    val setName: String,
    val generated: String,
    val dataFormat: String,
    val mechanics: List<GuideMechanic>,
    val archetypes: List<GuideArchetype>,
    val combos: List<GuideCombo>,
    val topCards: List<GuideColorCards>,
    val principles: List<GuidePrinciple>,
    val sources: List<GuideSource>,
)

object LimitedGuidance {
    private const val DRAFT_BASICS = "draft-basics"
    private const val DRAFT_SIGNALS = "draft-signals"
    private const val LIMITED_THEMES = "limited-themes"
    private const val DRAFT_HARD_WAY = "draft-hard-way"
    private const val METRICS = "17lands-metrics"
    private const val SEALED_FORMAT = "sealed-format"
    private const val SEALED_DUKE = "sealed-duke"
    private const val SEALED_INTRO = "sealed-introduction"
    private const val MANA_BASICS = "mana-basics"
    private const val MANA_SOURCES = "mana-sources"
    private const val MAKING_A_SPLASH = "making-a-splash"
    private const val LIMITED_SIDEBOARDING = "limited-sideboarding"
    internal const val metricsSourceId = METRICS

    val draftPrinciples: List<GuidePrinciple> = listOf(
        GuidePrinciple(
            id = "power-and-flexibility",
            title = "Take power early; stay flexible",
            summary = "Bombs, premium interaction, and flexible cards lead early. Do not lock into a color because of one pick.",
            appliedByFirstPick = "Raw card quality dominates early; color penalties and deck-fit pressure ramp up as the draft develops.",
            sourceIds = listOf(DRAFT_BASICS, DRAFT_HARD_WAY),
        ),
        GuidePrinciple(
            id = "signals",
            title = "Read repeated signals",
            summary = "A standout card arriving late is evidence of an open lane. Confirm that signal across several picks before moving in.",
            appliedByFirstPick = "Passed-card quality is accumulated by color and blended with your pool commitment and pair performance.",
            sourceIds = listOf(DRAFT_SIGNALS, DRAFT_HARD_WAY),
        ),
        GuidePrinciple(
            id = "deck-shape",
            title = "Draft a functioning deck",
            summary = "Protect your creature count, early plays, interaction, curve, and ways to close—not just the average rating of your pile.",
            appliedByFirstPick = "Later picks receive contextual value for filling creature, two-drop, removal, fixing, and finisher needs.",
            sourceIds = listOf(DRAFT_BASICS),
        ),
        GuidePrinciple(
            id = "supported-synergy",
            title = "Synergy needs support",
            summary = "Treat signposts as directions, not commands. Payoffs improve only when your pool contains the enablers and playables they require.",
            appliedByFirstPick = "Archetype and combo bonuses are gated by matching cards already in your pool and capped below raw power.",
            sourceIds = listOf(LIMITED_THEMES, DRAFT_BASICS),
        ),
        GuidePrinciple(
            id = "mana-discipline",
            title = "Respect the mana",
            summary = "Default to a castable two-color core. Splash only impactful, light-pip cards when the power gain justifies the fixing and tempo cost.",
            appliedByFirstPick = "The model cannot promote unsupported off-lane cards once a deck is projectable; completed splashes must meet pip- and timing-aware colored-source targets.",
            sourceIds = listOf(MANA_BASICS, MANA_SOURCES, MAKING_A_SPLASH),
        ),
    )

    val sealedPrinciples: List<GuidePrinciple> = listOf(
        GuidePrinciple(
            id = "sealed-compare-builds",
            title = "Compare several color cores",
            summary = "Sort by color, then compare depth, creature curve, interaction, and mana before letting one bomb decide the build.",
            appliedByFirstPick = "The Sealed policy evaluates every supported color core and gives additional weight to reliable mana, answers, finishers, and stall-breakers.",
            sourceIds = listOf(SEALED_DUKE, SEALED_INTRO),
        ),
        GuidePrinciple(
            id = "sealed-forty",
            title = "Start at ${LimitedPolicy.DECK_SIZE} cards",
            summary = "Use ${LimitedPolicy.LAND_SLOTS} lands and ${LimitedPolicy.SPELL_SLOTS} spells as the baseline. Extra cards reduce how often you draw your best ones.",
            appliedByFirstPick = "Projected decks target exactly ${LimitedPolicy.DECK_SIZE} cards and never pad missing spell slots with imaginary cards.",
            sourceIds = listOf(SEALED_FORMAT, SEALED_DUKE),
        ),
        GuidePrinciple(
            id = "sealed-board",
            title = "Build for the board and the curve",
            summary = "Play enough creatures and early interaction to avoid falling behind, then add evasion, card advantage, and finishers to break stalls.",
            appliedByFirstPick = "The distinct Sealed policy scores creature density, early plays, removal, draw, evasion, finishers, and ways to break stalled boards.",
            sourceIds = listOf(SEALED_DUKE),
        ),
        GuidePrinciple(
            id = "sealed-mana",
            title = "Two colors first; splash carefully",
            summary = "Prefer a reliable two-color base. Splash a small number of powerful single-pip cards only with credible sources or fixing.",
            appliedByFirstPick = "The builder allocates actual land slots, counts overlapping nonbasic sources and timely fixing, and rejects a splash with any colored-source shortfall.",
            sourceIds = listOf(SEALED_DUKE, MANA_BASICS, MANA_SOURCES, MAKING_A_SPLASH),
        ),
        GuidePrinciple(
            id = "sealed-answers",
            title = "Prioritize bombs, answers, and stall-breakers",
            summary = "After mana and curve are sound, maximize premium threats, broad removal, evasion, card advantage, and useful mana sinks.",
            appliedByFirstPick = "Sample-aware card quality is combined with structural bonuses and penalties rather than used as a pick order alone.",
            sourceIds = listOf(SEALED_DUKE),
        ),
        GuidePrinciple(
            id = "sealed-sideboard",
            title = "Use the whole pool as a sideboard",
            summary = "Revisit the pool between games for narrow answers, faster defense, a stronger long game, or even an alternate color build.",
            appliedByFirstPick = "Alternative builds remain visible so close color cores can be compared instead of discarded.",
            sourceIds = listOf(SEALED_FORMAT, LIMITED_SIDEBOARDING),
        ),
    )

    private val basicsOfManaSource = GuideSource(
        id = MANA_BASICS,
        title = "The Basics of Mana",
        publisher = "Wizards of the Coast",
        author = "Reid Duke",
        date = "2014-08-18",
        url = "https://magic.wizards.com/en/news/feature/basics-mana-2014-08-18",
        kind = "Official strategy",
    )

    private val manaSourcesSource = GuideSource(
        id = MANA_SOURCES,
        title = "How Many Sources Do You Need to Consistently Cast Your Spells?",
        publisher = "TCGplayer",
        author = "Frank Karsten",
        date = "2022-08-02",
        url = "https://www.tcgplayer.com/content/article/How-Many-Sources-Do-You-Need-to-Consistently-Cast-Your-Spells-A-2022-Update/dc23a7d2-0a16-4c0b-ad36-586fcca03ad8/",
        kind = "Pro analysis",
    )

    private val makingASplashSource = GuideSource(
        id = MAKING_A_SPLASH,
        title = "Making a Splash",
        publisher = "Wizards of the Coast",
        author = "Gavin Verhey",
        date = "2017-04-26",
        url = "https://magic.wizards.com/en/news/feature/making-splash-2017-04-26",
        kind = "Official strategy",
    )

    val draftSources: List<GuideSource> = listOf(
        GuideSource(
            "The Basics of Booster Draft",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/basics-booster-draft-2015-08-03",
            kind = "Official strategy",
            id = DRAFT_BASICS,
            author = "Reid Duke",
            date = "2015-08-03",
        ),
        GuideSource(
            "Signals in Booster Draft",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/signals-booster-draft-2015-01-19",
            kind = "Official strategy",
            id = DRAFT_SIGNALS,
            author = "Reid Duke",
            date = "2015-01-19",
        ),
        GuideSource(
            "Nuts & Bolts: Limited Themes",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/news/making-magic/nuts-bolts-12-part-2-limited-themes-2020-03-16",
            kind = "Official design",
            id = LIMITED_THEMES,
            author = "Mark Rosewater",
            date = "2020-03-16",
        ),
        GuideSource(
            "Drafting the Hard Way",
            "Limited Resources 507",
            "https://podcasts.apple.com/us/podcast/limited-resources-507-still-drafting-the-hard-way-with/id385051731?i=1000448129535",
            kind = "Podcast",
            id = DRAFT_HARD_WAY,
            author = "Ben Stark, Marshall Sutcliffe, and Luis Scott-Vargas",
            date = "2019-08-29",
        ),
        basicsOfManaSource,
        manaSourcesSource,
        makingASplashSource,
        GuideSource(
            "Metrics definitions",
            "17Lands",
            "https://www.17lands.com/metrics_definitions",
            kind = "Data reference",
            id = METRICS,
            author = "17Lands contributors",
        ),
    )

    val sealedSources: List<GuideSource> = listOf(
        GuideSource(
            "Sealed Deck format",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/formats/sealed-deck",
            kind = "Format reference",
            id = SEALED_FORMAT,
            author = "Wizards of the Coast",
        ),
        GuideSource(
            "Sealed Deck",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/sealed-deck-2014-09-15",
            kind = "Official strategy",
            id = SEALED_DUKE,
            author = "Reid Duke",
            date = "2014-09-15",
        ),
        GuideSource(
            "An Introduction to Sealed Deck",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/news/making-magic/introduction-sealed-deck-2012-10-29",
            kind = "Official strategy",
            id = SEALED_INTRO,
            author = "Nate Price",
            date = "2012-10-29",
        ),
        basicsOfManaSource,
        manaSourcesSource,
        makingASplashSource,
        GuideSource(
            "Sideboarding in Limited",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/sideboarding-limited-2015-01-12",
            kind = "Official strategy",
            id = LIMITED_SIDEBOARDING,
            author = "Reid Duke",
            date = "2015-01-13",
        ),
    )
}

object SetDraftGuideBuilder {
    private const val TOP_PER_GROUP = 3
    private val COLOR_ORDER = "WUBRG"
    private val NON_COMMON_RARITIES = setOf("uncommon", "rare", "mythic", "mythic rare")
    private val BEST_CARD_FIRST = compareByDescending<CardRating> { it.hasReliableWinRate }
        .thenByDescending { it.gihWr ?: Double.NEGATIVE_INFINITY }
        .thenByDescending { it.everDrawnGameCount }
        .thenBy { it.name }

    fun build(
        setCode: String,
        dataFormat: String,
        ratings: List<CardRating>,
        synergy: SynergyIndex?,
        pairStrength: Map<String, Double> = emptyMap(),
    ): SetDraftGuide {
        val normalizedSet = setCode.trim().uppercase()
        // Repository loads are asynchronous. Fail closed if a caller hands us
        // the previous set's index instead of publishing mislabeled guidance.
        val observedCardSource = GuideSource(
            id = "17lands-card-data-${normalizedSet.lowercase()}-${dataFormat.lowercase().filter(Char::isLetterOrDigit)}",
            title = "17Lands card data · $normalizedSet · $dataFormat",
            publisher = "17Lands",
            author = "17Lands contributors",
            url = SeventeenLandsClient.ratingsUrl(normalizedSet, dataFormat),
            kind = "Observed card data",
        )
        val reservedSourceIds = LimitedGuidance.draftSources.mapTo(mutableSetOf()) { it.id }
            .apply { add(observedCardSource.id) }
        val profile = synergy?.profile?.takeIf {
            it.set.equals(normalizedSet, ignoreCase = true) &&
                it.sources.none { source -> source.id in reservedSourceIds }
        }
        val archetypes = if (profile != null) {
            profile.archetypes.map { archetype ->
                GuideArchetype(
                    pair = archetype.pair,
                    name = archetype.name,
                    speed = archetype.speed,
                    plan = archetype.playstyle,
                    winRate = pairStrength[archetype.pair],
                    signposts = archetype.signposts,
                    enablers = archetype.enablers,
                    payoffs = archetype.payoffs,
                    keyCards = archetype.keyCards,
                    sourceIds = archetype.sourceIds,
                )
            }
        } else {
            COLOR_PAIRS.filter { it in pairStrength }.map { pair ->
                GuideArchetype(pair, "Data-only archetype", "", "Theme notes are not available for this set yet.", pairStrength[pair], emptyList(), emptyList(), emptyList(), emptyList())
            }
        }
        return SetDraftGuide(
            setCode = normalizedSet,
            setName = profile?.setName?.takeIf(String::isNotBlank) ?: normalizedSet,
            generated = profile?.updatedAt?.takeIf(String::isNotBlank) ?: profile?.generated.orEmpty(),
            dataFormat = dataFormat,
            mechanics = profile?.mechanics.orEmpty().map { GuideMechanic(it.name, it.summary, it.sourceIds) },
            archetypes = archetypes,
            combos = profile?.combos.orEmpty().map { GuideCombo(it.cards, it.note, it.sourceIds) },
            topCards = COLOR_ORDER.map { color ->
                val candidates = ratings.filter { rating -> rating.isGuideCandidate(color) }
                GuideColorCards(
                    color = color,
                    commons = candidates.filter { it.rarity.equals("common", ignoreCase = true) }
                        .sortedWith(BEST_CARD_FIRST).take(TOP_PER_GROUP).map { it.toGuideCard(color) },
                    nonCommons = candidates.filter { it.rarity.lowercase() in NON_COMMON_RARITIES }
                        .sortedWith(BEST_CARD_FIRST).take(TOP_PER_GROUP).map { it.toGuideCard(color) },
                    sourceIds = listOf(observedCardSource.id, LimitedGuidance.metricsSourceId),
                )
            },
            principles = LimitedGuidance.draftPrinciples,
            sources = (
                profile?.sources.orEmpty().map { source ->
                    GuideSource(
                        title = source.title,
                        publisher = source.publisher,
                        url = source.url,
                        kind = source.kind.guideLabel(),
                        id = source.id,
                        author = source.author,
                        date = source.date,
                    )
                } + LimitedGuidance.draftSources + observedCardSource
            ).distinctBy { it.id.ifBlank { it.url } },
        )
    }

    private fun CardRating.isGuideCandidate(color: Char): Boolean =
        name.isNotBlank() && mtgaId != null && this.color == color.toString() &&
            types.none { it.contains("Land", ignoreCase = true) } &&
            rarity.isNotBlank() && (gihWr != null || alsa != null)

    private fun CardRating.toGuideCard(color: Char) = GuideCard(
        name = name,
        color = color,
        rarity = rarity,
        gihWr = gihWr,
        games = everDrawnGameCount,
        imageUrl = imageUrl.takeIf(String::isNotBlank),
    )

    private fun SynergySourceKind.guideLabel(): String = when (this) {
        SynergySourceKind.OFFICIAL_MECHANICS -> "Official mechanics"
        SynergySourceKind.OFFICIAL_LIMITED_GUIDE -> "Official Limited guide"
        SynergySourceKind.OFFICIAL_RELEASE_NOTES -> "Official release notes"
        SynergySourceKind.LIMITED_DATA -> "Limited data"
    }
}
