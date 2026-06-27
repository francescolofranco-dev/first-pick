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
import com.firstpick.cards.DataUnavailableException
import com.firstpick.cards.FetchFailure
import com.firstpick.core.AppPaths
import com.firstpick.draft.DraftTracker
import com.firstpick.log.LogWatcher
import com.firstpick.model.DraftState
import com.firstpick.signals.SignalsEngine
import com.firstpick.sim.DraftSimulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val simulator: DraftSimulator = DraftSimulator(),
) {
    private val _ui = MutableStateFlow(DraftUiState())
    val ui: StateFlow<DraftUiState> = _ui.asStateFlow()

    /**
     * Serializes every `_ui` write and the load-once decision. Without it, the collect
     * loop, the background data-load, and setFormatChoice all raced on `_ui`/[requestedKey]
     * and could clobber each other's state.
     */
    private val mutex = Mutex()
    private var requestedKey: String? = null // guarded by [mutex]
    @Volatile private var currentError: String? = null

    private var watcherJob: Job? = null
    private var simJob: Job? = null
    @Volatile private var simulating = false
    private val simPaused = MutableStateFlow(false)

    /** User-selected 17Lands data source ([RatingsFormat]); persisted across runs. */
    @Volatile
    private var formatChoice: String =
        runCatching { OverlaySettings.load().ratingsFormatOverride }.getOrNull() ?: RatingsFormat.PREMIER

    fun start() {
        watcherJob = scope.launch(Dispatchers.IO) { tracker.consume(watcher.lines(fromStart = true)) }
        scope.launch {
            tracker.state.collect { state ->
                state.setCode?.let { ensureLoaded(it) }
                ensureLanePair(state)
                publish()
            }
        }
    }

    /**
     * Demo/test mode: stop tailing the real log and drive the app from a simulated
     * draft of [set] (see [DraftSimulator]). The synthetic snapshots flow through the
     * normal pipeline, so the UI behaves exactly as in a live draft.
     */
    fun startSimulation(set: String) {
        simJob?.cancel()
        watcherJob?.cancel()
        simulating = true
        simPaused.value = false
        scope.launch { publish() } // reflect "simulating" immediately, before the first pick
        simJob = scope.launch(Dispatchers.IO) {
            tracker.consume(simulator.simulate(set, paused = simPaused))
            // The flow finished with nothing → the set had no 17Lands data to simulate.
            if (tracker.state.value.setCode == null) {
                currentError = "No 17Lands data to simulate ${set.uppercase()}"
                simulating = false
                publish()
            }
        }
    }

    /** Pause or resume an in-progress demo. Paused keeps all draft state in place. */
    fun toggleSimulationPause() {
        if (!simulating) return
        simPaused.value = !simPaused.value
        scope.launch { publish() }
    }

    /** Exit the demo entirely and resume tailing the live Arena log. */
    fun stopSimulation() {
        if (!simulating) return
        simJob?.cancel()
        simJob = null
        simulating = false
        simPaused.value = false
        watcherJob = scope.launch(Dispatchers.IO) { tracker.consume(watcher.lines(fromStart = false)) }
        scope.launch { publish() }
    }

    /**
     * Change the 17Lands data source. Persists the choice and reloads ratings for
     * the active set (cheap if already cached on disk).
     */
    fun setFormatChoice(choice: String) {
        if (choice == formatChoice) return
        formatChoice = choice
        runCatching { OverlaySettings.save(OverlaySettings.load().copy(ratingsFormatOverride = choice)) }
        scope.launch {
            mutex.withLock { requestedKey = null } // force a reload under the new format
            tracker.state.value.setCode?.let { ensureLoaded(it) }
            publish()
        }
    }

    /** Rebuild and publish the UI from the latest state, atomically. */
    private suspend fun publish() = mutex.withLock {
        _ui.value = buildUi(tracker.state.value).copy(dataError = currentError)
    }

    private suspend fun ensureLoaded(set: String) {
        val format = RatingsFormat.resolve(formatChoice, tracker.state.value.format)
        val key = "${set}_$format"
        val proceed = mutex.withLock { if (key == requestedKey) false else { requestedKey = key; true } }
        if (!proceed) return
        currentError = null
        mutex.withLock { _ui.value = _ui.value.copy(loadingRatings = true, dataError = null) }

        val outcome = runCatching { repo.load(set, format) }
        currentError = outcome.exceptionOrNull()?.let { dataErrorMessage(it, set) }
            ?: if (repo.isLoaded) null else dataErrorMessage(null, set)
        publish()

        // Scryfall mana values + archetype strengths in the background (optional data).
        scope.launch {
            runCatching { metaRepo.load(set) }
            runCatching { archetypeRepo.loadStrengths(set, format) }
            publish()
        }
    }

    /** Lazily fetch the archetype-specific card data for the lane the pool points at. */
    private suspend fun ensureLanePair(state: DraftState) {
        val set = state.setCode ?: return
        if (!repo.isLoaded) return
        val pool = state.pool.map(repo::resolve)
        val signals = SignalsEngine.openLanes(state.seen, repo::resolve)
        val pair = LaneDetector.detect(pool, repo.setMetrics, archetypeRepo.strengthMap(), signals).pair ?: return
        val format = RatingsFormat.resolve(formatChoice, state.format)
        runCatching { archetypeRepo.ensurePair(set, format, pair) }
    }

    private fun buildUi(state: DraftState): DraftUiState {
        val loaded = repo.isLoaded
        val pool = if (loaded) state.pool.map(repo::resolve) else emptyList()
        // Open-lane signals (cards flowing to you) now bias lane detection, not just the UI.
        val signals = if (loaded) SignalsEngine.openLanes(state.seen, repo::resolve) else emptyMap()
        val lane = if (loaded) {
            LaneDetector.detect(pool, repo.setMetrics, archetypeRepo.strengthMap(), signals)
        } else {
            Lane(emptySet(), null, emptyMap())
        }

        val poolMetas = pool.mapNotNull { metaRepo.meta(it.name) }
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
            ).toRows(state.packCards)
        } else {
            emptyList()
        }

        val openLanes = signals.toColorScores()
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
            dataError = null, // set by publish() from currentError
            packCards = rows,
            laneColors = WUBRG_ORDER.filter { it in lane.colors },
            openLanes = openLanes,
            manaCurve = manaCurveOf(poolMetas),
            poolColorCounts = poolColorCounts(pool),
            poolCreatures = poolMetas.count { it.isCreature },
            poolNonCreatures = poolMetas.count { !it.isCreature && !it.isLand },
            lanePair = lane.pair,
            topPairs = lane.topPairs,
            archetypes = archetypeRows(lane.pair),
            deckNeeds = deckNeeds,
            deckOptions = deckOptions,
            ratingsFormatChoice = formatChoice,
            simulating = simulating,
            simPaused = simPaused.value,
        )
    }

    private fun DeckOption.toUi(): DeckOptionUi {
        val basicsLine = basics.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.value}${it.key}" }
        return DeckOptionUi(
            pair = pair,
            tier = tier,
            type = type,
            outlook = outlook,
            power = powerScore.toInt(),
            creatures = creatures,
            removal = removal,
            landLine = basicsLine.ifBlank { "—" },
            spells = spells.toDeckSpells(),
            lands = nonbasicLands.toDeckSpells(),
        )
    }

    /** Deduplicate cards by name (with a copy count) and annotate type + role. */
    private fun List<com.firstpick.cards.RankedCard>.toDeckSpells(): List<DeckSpellUi> =
        groupBy { it.displayName }.map { (name, copies) ->
            val c = copies.first()
            val m = metaRepo.meta(c.name)
            DeckSpellUi(
                name = name,
                count = copies.size,
                cmc = m?.cmc ?: 0,
                color = c.rating?.color.orEmpty(),
                gihWr = c.gihWr,
                imageUrl = c.rating?.imageUrl,
                typeLabel = deckCardType(c.rating, m),
                role = deckCardRole(m),
                isLand = m?.isLand == true,
            )
        }.sortedWith(compareBy({ it.cmc }, { -(it.gihWr ?: 0.0) }))

    private fun deckCardType(rating: com.firstpick.cards.CardRating?, meta: CardMeta?): String {
        val types = rating?.types.orEmpty()
        fun has(t: String) = types.any { it.equals(t, ignoreCase = true) }
        return when {
            meta?.isLand == true || has("Land") -> "Land"
            meta?.isCreature == true || has("Creature") -> "Creature"
            has("Instant") -> "Instant"
            has("Sorcery") -> "Sorcery"
            has("Planeswalker") -> "Planeswalker"
            has("Enchantment") -> "Enchantment"
            has("Artifact") -> "Artifact"
            has("Battle") -> "Battle"
            else -> "Spell"
        }
    }

    /** The single most salient functional role for a card (priority order), or null. */
    private fun deckCardRole(meta: CardMeta?): String? = when {
        meta == null -> null
        meta.isRemoval -> "Removal"
        meta.isFixing -> "Fixing"
        meta.isFinisher -> "Finisher"
        meta.isCardDraw -> "Draw"
        meta.isEvasion -> "Evasion"
        else -> null
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

    private fun List<ScoredCard>.toRows(originalPackIds: List<Int>): List<PackCardUi> {
        val usedIndices = mutableSetOf<Int>()
        return mapIndexed { i, s ->
            val origIdx = originalPackIds.indexOfFirst { it == s.card.grpId && it !in usedIndices }.takeIf { it >= 0 } ?: 0
            usedIndices.add(origIdx)
            PackCardUi(
                grpId = s.card.grpId,
                originalIndex = origIdx,
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
                z = s.z,
                breakdown = s.breakdown,
            )
        }
    }

    private fun dataErrorMessage(t: Throwable?, set: String): String =
        ratingsErrorMessage((t as? DataUnavailableException)?.reason, set)

    companion object {
        private const val TOTAL_PICKS = 45
        private val WUBRG_ORDER = listOf('W', 'U', 'B', 'R', 'G')
    }
}

/** Map a fetch failure to a user-facing message. Top-level + internal so it's testable. */
internal fun ratingsErrorMessage(reason: FetchFailure?, set: String): String = when (reason) {
    FetchFailure.RATE_LIMITED -> "17Lands is rate-limiting — retrying; using cached data if available"
    FetchFailure.OFFLINE -> "Can't reach 17Lands — check your connection"
    FetchFailure.SERVER_ERROR -> "17Lands is having issues — retrying shortly"
    FetchFailure.NOT_FOUND -> "No 17Lands data for $set yet"
    FetchFailure.BAD_DATA -> "17Lands returned unexpected data for $set"
    null -> "Couldn't load 17Lands data for $set"
}
