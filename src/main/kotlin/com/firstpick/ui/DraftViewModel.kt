package com.firstpick.ui

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.DeckBuilder
import com.firstpick.advisor.DeckOption
import com.firstpick.advisor.Lane
import com.firstpick.advisor.LaneDetector
import com.firstpick.advisor.PoolNeeds
import com.firstpick.advisor.ScoredCard
import com.firstpick.advisor.WUBRG
import com.firstpick.model.DraftPhase
import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.core.AppPaths
import com.firstpick.draft.DraftTracker
import com.firstpick.log.LogWatcher
import com.firstpick.model.DraftState
import com.firstpick.signals.SignalsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Wires the live pipeline: tail Player.log -> reconstruct draft state -> load
 * 17Lands ratings + archetype data (+ Scryfall mana values) for the active set ->
 * run the contextual advisor -> publish a [DraftUiState] for Compose.
 */
class DraftViewModel(
    private val scope: CoroutineScope,
    logPath: Path = AppPaths.defaultPlayerLog,
    private val watcher: LogWatcher = LogWatcher(logPath),
    private val tracker: DraftTracker = DraftTracker(),
    private val repo: CardRepository = CardRepository(),
    private val metaRepo: CardMetaRepository = CardMetaRepository(),
    private val archetypeRepo: ArchetypeRepository = ArchetypeRepository(),
    private val advisor: AdvisorEngine = AdvisorEngine(),
) {
    private val _ui = MutableStateFlow(DraftUiState())
    val ui: StateFlow<DraftUiState> = _ui.asStateFlow()

    private var requestedKey: String? = null

    fun start() {
        scope.launch(Dispatchers.IO) { tracker.consume(watcher.lines(fromStart = true)) }
        scope.launch {
            tracker.state.collect { state ->
                state.setCode?.let { ensureLoaded(it) }
                ensureLanePair(state)
                _ui.value = buildUi(tracker.state.value)
            }
        }
    }

    private suspend fun ensureLoaded(set: String) {
        val key = "${set}_$RATINGS_FORMAT"
        if (key == requestedKey) return
        requestedKey = key
        _ui.value = _ui.value.copy(loadingRatings = true, dataError = null)

        val ok = runCatching { repo.load(set, RATINGS_FORMAT) }.isSuccess
        _ui.value = buildUi(tracker.state.value)
            .copy(dataError = if (ok && repo.isLoaded) null else "Couldn't load 17Lands data for $set")

        // Scryfall mana values + archetype strengths in the background.
        scope.launch {
            runCatching { metaRepo.load(set) }
            runCatching { archetypeRepo.loadStrengths(set, RATINGS_FORMAT) }
            _ui.value = buildUi(tracker.state.value)
        }
    }

    /** Lazily fetch the archetype-specific card data for the lane the pool points at. */
    private suspend fun ensureLanePair(state: DraftState) {
        val set = state.setCode ?: return
        if (!repo.isLoaded) return
        val pool = state.pool.map(repo::resolve)
        val pair = LaneDetector.detect(pool, repo.setMetrics, archetypeRepo.strengthMap()).pair ?: return
        runCatching { archetypeRepo.ensurePair(set, RATINGS_FORMAT, pair) }
    }

    private fun buildUi(state: DraftState): DraftUiState {
        val loaded = repo.isLoaded
        val pool = if (loaded) state.pool.map(repo::resolve) else emptyList()
        val lane = if (loaded) {
            LaneDetector.detect(pool, repo.setMetrics, archetypeRepo.strengthMap())
        } else {
            Lane(emptySet(), null, emptyMap())
        }

        val rows = if (loaded && state.packCards.isNotEmpty()) {
            advisor.score(
                pack = repo.resolvePack(state.packCards),
                pool = pool,
                packNumber = state.pack.coerceAtLeast(1),
                pickNumber = state.pick.coerceAtLeast(1),
                metrics = repo.setMetrics,
                lane = lane,
                archetypeRating = archetypeRepo::archetypeRating,
                meta = metaRepo::meta,
            ).toRows()
        } else {
            emptyList()
        }

        val openLanes = if (loaded) SignalsEngine.openLanes(state.seen, repo::resolve).toColorScores() else emptyList()
        val poolMetas = pool.mapNotNull { metaRepo.meta(it.name) }
        val deckNeeds = if (loaded && state.pool.isNotEmpty()) {
            PoolNeeds.analyze(poolMetas, state.pool.size).activeNeeds(TOTAL_PICKS)
        } else {
            emptyList()
        }

        // Decks show when the draft completes; FIRSTPICK_FORCE_DECKS=1 previews them anytime.
        val deckReady = state.phase == DraftPhase.COMPLETE || System.getenv("FIRSTPICK_FORCE_DECKS") == "1"
        val deckOptions = if (loaded && deckReady && state.pool.size >= 20) {
            DeckBuilder.build(
                pool = pool,
                metrics = repo.setMetrics,
                meta = metaRepo::meta,
                archetypeRating = archetypeRepo::archetypeRating,
                pairStrength = archetypeRepo.strengthMap(),
            ).map { it.toUi() }
        } else {
            emptyList()
        }

        return DraftUiState(
            phase = state.phase,
            setCode = state.setCode,
            format = state.format,
            pack = state.pack,
            pick = state.pick,
            poolSize = state.pool.size,
            loadingRatings = state.setCode != null && !loaded,
            dataError = _ui.value.dataError,
            packCards = rows,
            laneColors = WUBRG_ORDER.filter { it in lane.colors },
            openLanes = openLanes,
            manaCurve = manaCurveOf(poolMetas),
            poolColorCounts = poolColorCounts(pool),
            poolCreatures = poolMetas.count { it.isCreature },
            poolNonCreatures = poolMetas.count { !it.isCreature && !it.isLand },
            lanePair = lane.pair,
            archetypes = archetypeRows(lane.pair),
            deckNeeds = deckNeeds,
            deckOptions = deckOptions,
        )
    }

    private fun DeckOption.toUi(): DeckOptionUi {
        val basicsLine = basics.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.value}${it.key}" }
        val nonbasic = if (nonbasicLands.isNotEmpty()) " +${nonbasicLands.size} nonbasic" else ""
        return DeckOptionUi(
            pair = pair,
            tier = tier,
            type = type,
            outlook = outlook,
            power = powerScore.toInt(),
            creatures = creatures,
            removal = removal,
            landLine = (basicsLine.ifBlank { "—" }) + nonbasic,
            spells = spells.map {
                DeckSpellUi(
                    name = it.displayName,
                    cmc = metaRepo.meta(it.name)?.cmc ?: 0,
                    color = it.rating?.color.orEmpty(),
                    gihWr = it.gihWr,
                    imageUrl = it.rating?.imageUrl,
                )
            },
        )
    }

    private fun archetypeRows(lanePair: String?): List<ArchetypeRow> =
        archetypeRepo.rankedPairs().map { ArchetypeRow(it.pair, it.winRate, it.pair == lanePair) }

    private fun Map<Char, Double>.toColorScores(): List<ColorScore> =
        entries.filter { it.value > 0.0 }.sortedByDescending { it.value }.map { ColorScore(it.key, it.value) }

    private fun poolColorCounts(pool: List<com.firstpick.cards.RankedCard>): List<ColorScore> {
        val counts = mutableMapOf<Char, Int>()
        for (card in pool) for (ch in card.rating?.color.orEmpty()) if (ch in WUBRG) counts.merge(ch, 1, Int::plus)
        return WUBRG_ORDER.filter { counts.containsKey(it) }.map { ColorScore(it, counts.getValue(it).toDouble()) }
    }

    private fun manaCurveOf(metas: List<CardMeta>): List<CurveBar> {
        val buckets = linkedMapOf("≤1" to 0, "2" to 0, "3" to 0, "4" to 0, "5" to 0, "6+" to 0)
        for (m in metas.filter { !it.isLand }) {
            val key = when {
                m.cmc <= 1 -> "≤1"
                m.cmc >= 6 -> "6+"
                else -> m.cmc.toString()
            }
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        return buckets.map { CurveBar(it.key, it.value) }
    }

    private fun List<ScoredCard>.toRows(): List<PackCardUi> = mapIndexed { i, s ->
        PackCardUi(
            grpId = s.card.grpId,
            rank = i + 1,
            name = s.card.displayName,
            color = s.card.rating?.color.orEmpty(),
            rarity = s.card.rating?.rarity.orEmpty(),
            gihWr = s.card.gihWr,
            alsa = s.card.rating?.alsa,
            ata = s.card.rating?.ata,
            value = s.value,
            isBomb = s.isBomb,
            reasons = s.reasons,
            imageUrl = s.card.rating?.imageUrl,
        )
    }

    companion object {
        /**
         * Card-quality data source. Premier Draft is the deepest, most stable
         * sample, so we use it for evaluation even in Quick Draft (card quality
         * transfers). TODO: make this user-selectable.
         */
        const val RATINGS_FORMAT = "PremierDraft"

        private const val TOTAL_PICKS = 45
        private val WUBRG_ORDER = listOf('W', 'U', 'B', 'R', 'G')
    }
}
