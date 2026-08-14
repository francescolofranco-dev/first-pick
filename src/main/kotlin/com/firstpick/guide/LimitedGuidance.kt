package com.firstpick.guide

import com.firstpick.advisor.COLOR_PAIRS
import com.firstpick.cards.CardRating
import com.firstpick.cards.SynergyIndex

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
)

data class GuideSource(
    val title: String,
    val publisher: String,
    val url: String,
    val kind: String = "Article",
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
)

data class GuideMechanic(val name: String, val summary: String)

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
)

data class SetDraftGuide(
    val setCode: String,
    val setName: String,
    val generated: String,
    val dataFormat: String,
    val mechanics: List<GuideMechanic>,
    val archetypes: List<GuideArchetype>,
    val topCards: List<GuideColorCards>,
    val principles: List<GuidePrinciple>,
    val sources: List<GuideSource>,
)

object LimitedGuidance {
    val draftPrinciples: List<GuidePrinciple> = listOf(
        GuidePrinciple(
            id = "power-and-flexibility",
            title = "Take power early; stay flexible",
            summary = "Bombs, premium interaction, and flexible cards lead early. Do not lock into a color because of one pick.",
            appliedByFirstPick = "Raw card quality dominates early; color penalties and deck-fit pressure ramp up as the draft develops.",
        ),
        GuidePrinciple(
            id = "signals",
            title = "Read repeated signals",
            summary = "A standout card arriving late is evidence of an open lane. Confirm that signal across several picks before moving in.",
            appliedByFirstPick = "Passed-card quality is accumulated by color and blended with your pool commitment and pair performance.",
        ),
        GuidePrinciple(
            id = "deck-shape",
            title = "Draft a functioning deck",
            summary = "Protect your creature count, early plays, interaction, curve, and ways to close—not just the average rating of your pile.",
            appliedByFirstPick = "Later picks receive contextual value for filling creature, two-drop, removal, fixing, and finisher needs.",
        ),
        GuidePrinciple(
            id = "supported-synergy",
            title = "Synergy needs support",
            summary = "Treat signposts as directions, not commands. Payoffs improve only when your pool contains the enablers and playables they require.",
            appliedByFirstPick = "Archetype and combo bonuses are gated by matching cards already in your pool and capped below raw power.",
        ),
        GuidePrinciple(
            id = "mana-discipline",
            title = "Respect the mana",
            summary = "Default to a castable two-color core. Splash only impactful, light-pip cards when the power gain justifies the fixing and tempo cost.",
            appliedByFirstPick = "Off-color pressure rises over time; heavy-pip splashes are rejected and final builds cap splash cards.",
        ),
    )

    val sealedPrinciples: List<GuidePrinciple> = listOf(
        GuidePrinciple(
            id = "sealed-compare-builds",
            title = "Compare several color cores",
            summary = "Sort by color, then compare depth, creature curve, interaction, and mana before letting one bomb decide the build.",
            appliedByFirstPick = "The deck builder evaluates every supported color pair and ranks complete builds rather than the loudest individual card.",
        ),
        GuidePrinciple(
            id = "sealed-forty",
            title = "Start at ${LimitedPolicy.DECK_SIZE} cards",
            summary = "Use ${LimitedPolicy.LAND_SLOTS} lands and ${LimitedPolicy.SPELL_SLOTS} spells as the baseline. Extra cards reduce how often you draw your best ones.",
            appliedByFirstPick = "Projected decks target exactly ${LimitedPolicy.DECK_SIZE} cards and never pad missing spell slots with imaginary cards.",
        ),
        GuidePrinciple(
            id = "sealed-board",
            title = "Build for the board and the curve",
            summary = "Play enough creatures and early interaction to avoid falling behind, then add evasion, card advantage, and finishers to break stalls.",
            appliedByFirstPick = "Construction scores creature density, early plays, removal, draw, evasion, finishers, and pace-specific curves.",
        ),
        GuidePrinciple(
            id = "sealed-mana",
            title = "Two colors first; splash carefully",
            summary = "Prefer a reliable two-color base. Splash a small number of powerful single-pip cards only with credible sources or fixing.",
            appliedByFirstPick = "Builds enforce base-color representation, reject heavy splash pips, and penalize splash cards that lack fixing.",
        ),
        GuidePrinciple(
            id = "sealed-answers",
            title = "Prioritize bombs, answers, and stall-breakers",
            summary = "After mana and curve are sound, maximize premium threats, broad removal, evasion, card advantage, and useful mana sinks.",
            appliedByFirstPick = "Sample-aware card quality is combined with structural bonuses and penalties rather than used as a pick order alone.",
        ),
        GuidePrinciple(
            id = "sealed-sideboard",
            title = "Use the whole pool as a sideboard",
            summary = "Revisit the pool between games for narrow answers, faster defense, a stronger long game, or even an alternate color build.",
            appliedByFirstPick = "Alternative builds remain visible so close color cores can be compared instead of discarded.",
        ),
    )

    val draftSources: List<GuideSource> = listOf(
        GuideSource(
            "The Basics of Booster Draft",
            "Reid Duke · Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/basics-booster-draft-2015-08-03",
        ),
        GuideSource(
            "Signals in Booster Draft",
            "Reid Duke · Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/signals-booster-draft-2015-01-19",
        ),
        GuideSource(
            "Nuts & Bolts: Limited Themes",
            "Mark Rosewater · Wizards of the Coast",
            "https://magic.wizards.com/en/news/making-magic/nuts-bolts-12-part-2-limited-themes-2020-03-16",
        ),
        GuideSource(
            "Drafting the Hard Way",
            "Ben Stark · Limited Resources 507",
            "https://podcasts.apple.com/us/podcast/limited-resources-507-still-drafting-the-hard-way-with/id385051731?i=1000448129535",
            kind = "Podcast",
        ),
        GuideSource(
            "Metrics definitions",
            "17Lands",
            "https://www.17lands.com/metrics_definitions",
            kind = "Data reference",
        ),
    )

    val sealedSources: List<GuideSource> = listOf(
        GuideSource(
            "Sealed Deck format",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/formats/sealed-deck",
            kind = "Format reference",
        ),
        GuideSource(
            "Sealed Deck",
            "Reid Duke · Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/sealed-deck-2014-09-15",
        ),
        GuideSource(
            "An Introduction to Sealed Deck",
            "Wizards of the Coast",
            "https://magic.wizards.com/en/news/making-magic/introduction-sealed-deck-2012-10-29",
        ),
        GuideSource(
            "The Basics of Mana",
            "Reid Duke · Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/basics-mana-2014-08-18",
        ),
        GuideSource(
            "Sideboarding in Limited",
            "Reid Duke · Wizards of the Coast",
            "https://magic.wizards.com/en/news/feature/sideboarding-limited-2015-01-12",
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
        val profile = synergy?.profile?.takeIf { it.set.equals(normalizedSet, ignoreCase = true) }
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
            generated = profile?.generated.orEmpty(),
            dataFormat = dataFormat,
            mechanics = profile?.mechanics.orEmpty().map { GuideMechanic(it.name, it.summary) },
            archetypes = archetypes,
            topCards = COLOR_ORDER.map { color ->
                val candidates = ratings.filter { rating -> rating.isGuideCandidate(color) }
                GuideColorCards(
                    color = color,
                    commons = candidates.filter { it.rarity.equals("common", ignoreCase = true) }
                        .sortedWith(BEST_CARD_FIRST).take(TOP_PER_GROUP).map { it.toGuideCard(color) },
                    nonCommons = candidates.filter { it.rarity.lowercase() in NON_COMMON_RARITIES }
                        .sortedWith(BEST_CARD_FIRST).take(TOP_PER_GROUP).map { it.toGuideCard(color) },
                )
            },
            principles = LimitedGuidance.draftPrinciples,
            sources = LimitedGuidance.draftSources,
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
}
