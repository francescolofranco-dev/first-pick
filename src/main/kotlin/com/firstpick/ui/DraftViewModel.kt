package com.firstpick.ui

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.DeckBuilder
import com.firstpick.advisor.DeckOption
import com.firstpick.advisor.DeckProjector
import com.firstpick.advisor.Lane
import com.firstpick.advisor.LaneDetector
import com.firstpick.advisor.PickNetRanker
import com.firstpick.advisor.PoolNeeds
import com.firstpick.advisor.ScoredCard
import com.firstpick.advisor.WUBRG
import com.firstpick.model.DraftPhase
import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.cards.StandardSets
import com.firstpick.cards.SynergyRepository
import com.firstpick.cards.SynergyTierLevel
import com.firstpick.cards.DataUnavailableException
import com.firstpick.cards.FetchFailure
import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import com.firstpick.draft.DraftTracker
import com.firstpick.log.LogWatcher
import com.firstpick.model.DraftState
import com.firstpick.model.PickNetRepository
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

class DraftViewModel(
    private val scope: CoroutineScope,
    logPath: Path = AppPaths.defaultPlayerLog,
    private val watcher: LogWatcher = LogWatcher(logPath),
    private val tracker: DraftTracker = DraftTracker(),
    private val repo: CardRepository = CardRepository(),
    private val metaRepo: CardMetaRepository = CardMetaRepository(),
    private val archetypeRepo: ArchetypeRepository = ArchetypeRepository(),
    private val synergyRepo: SynergyRepository = SynergyRepository(),
    private val pickNetRepo: PickNetRepository = PickNetRepository(),
    private val advisor: AdvisorEngine = AdvisorEngine(),
    private val simulator: DraftSimulator = DraftSimulator(),
) {
    private val _ui = MutableStateFlow(DraftUiState())
    val ui: StateFlow<DraftUiState> = _ui.asStateFlow()

    private val mutex = Mutex()
    private var requestedKey: String? = null
    @Volatile private var currentError: String? = null

    private var watcherJob: Job? = null
    private var simJob: Job? = null
    @Volatile private var simulating = false
    private val simPaused = MutableStateFlow(false)

    @Volatile
    private var formatChoice: String =
        runCatching { OverlaySettings.load().ratingsFormatOverride }.getOrNull() ?: RatingsFormat.PREMIER

    fun start() {
        watcherJob = scope.launch(Dispatchers.IO) { tracker.consume(watcher.lines(fromStart = true)) }
        scope.launch {
            tracker.state.collect { state ->
                runCatching {
                    state.setCode?.let { ensureLoaded(it) }
                    ensureLanePair(state)
                    publish()
                }.onFailure { Log.warn(TAG, "pipeline step failed: $it") }
            }
        }
    }

    fun startSimulation(set: String) {
        simJob?.cancel()
        watcherJob?.cancel()
        tracker.reset()
        currentError = null
        simulating = true
        simPaused.value = false
        scope.launch { publish() }
        simJob = scope.launch(Dispatchers.IO) {
            tracker.consume(simulator.simulate(set, paused = simPaused))
            if (tracker.state.value.setCode == null) {
                currentError = "No 17Lands data to simulate ${set.uppercase()}"
                simulating = false
                publish()
            }
        }
    }

    fun toggleSimulationPause() {
        if (!simulating) return
        simPaused.value = !simPaused.value
        scope.launch { publish() }
    }

    fun stopSimulation() {
        if (!simulating) return
        simJob?.cancel()
        simJob = null
        simulating = false
        simPaused.value = false
        currentError = null
        tracker.reset()
        watcherJob = scope.launch(Dispatchers.IO) { tracker.consume(watcher.lines(fromStart = false)) }
        scope.launch { publish() }
    }

    fun setFormatChoice(choice: String) {
        if (choice == formatChoice) return
        formatChoice = choice
        runCatching { OverlaySettings.save(OverlaySettings.load().copy(ratingsFormatOverride = choice)) }
        scope.launch {
            mutex.withLock { requestedKey = null }
            tracker.state.value.setCode?.let { ensureLoaded(it) }
            publish()
        }
    }

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

        scope.launch {
            runCatching { metaRepo.load(set, repo.cardNames) }
            runCatching { archetypeRepo.loadStrengths(set, format) }
            runCatching { synergyRepo.load(set) }
            runCatching { pickNetRepo.load(set, format) }
            publish()
        }
    }

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
        val signals = if (loaded) SignalsEngine.openLanes(state.seen, repo::resolve) else emptyMap()
        val lane = if (loaded) {
            LaneDetector.detect(pool, repo.setMetrics, archetypeRepo.strengthMap(), signals)
        } else {
            Lane(emptySet(), null, emptyMap())
        }

        val poolMetas = pool.mapNotNull { metaRepo.meta(it.name) }
        val format = RatingsFormat.resolve(formatChoice, state.format)
        val net = pickNetRepo.netFor(state.setCode, format)
        // The deck this pool is building right now — shared by the pick-time deck-fit signal
        // and the "Deck so far" panel, so a card's projected fit always matches what's shown.
        // Gated on lane.isEstablished: an open pool has no meaningful "best deck" yet.
        val liveProjection = if (loaded && lane.isEstablished) {
            DeckProjector.project(pool, repo.setMetrics, metaRepo::meta, archetypeRepo::archetypeRating, archetypeRepo.strengthMap(), synergyRepo.index)
        } else {
            null
        }
        val rows = if (loaded && state.packCards.isNotEmpty()) {
            val scored = advisor.score(
                pack = repo.resolvePack(state.packCards),
                pool = pool,
                packNumber = state.pack.coerceAtLeast(1),
                pickNumber = state.pick.coerceAtLeast(1),
                metrics = repo.setMetrics,
                lane = lane,
                archetypeRating = archetypeRepo::archetypeRating,
                meta = metaRepo::meta,
                synergy = synergyRepo.index,
                deckFit = deckFitProbe(pool, liveProjection),
            )
            val ranked = net?.let { PickNetRanker.rerank(it, scored, pool.map { c -> c.name }) } ?: scored
            ranked.toRows(state.packCards)
        } else {
            emptyList()
        }

        val openLanes = signals.toColorScores()
        val deckNeeds = if (loaded && state.pool.isNotEmpty()) {
            PoolNeeds.analyze(poolMetas, state.pool.size).activeNeeds(TOTAL_PICKS)
        } else {
            emptyList()
        }

        val deckReady = state.phase == DraftPhase.COMPLETE || System.getenv("FIRSTPICK_FORCE_DECKS") == "1"
        val deckOptions = if (loaded && deckReady && state.pool.size >= 20) {
            runCatching {
                DeckBuilder.build(
                    pool = pool,
                    metrics = repo.setMetrics,
                    meta = metaRepo::meta,
                    archetypeRating = archetypeRepo::archetypeRating,
                    pairStrength = archetypeRepo.strengthMap(),
                    synergy = synergyRepo.index,
                ).map { it.toUi() }
            }.getOrElse { Log.warn(TAG, "deck build failed: $it"); emptyList() }
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
            dataError = null,
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
            deckSoFar = liveProjection?.toUi(),
            deckSoFarCuts = cutsOf(pool, liveProjection),
            ratingsFormatChoice = formatChoice,
            simulating = simulating,
            simPaused = simPaused.value,
            synergyTier = state.setCode?.let {
                when (StandardSets.tier(it)) {
                    SynergyTierLevel.RESEARCHED -> "researched"
                    SynergyTierLevel.DATA -> "data"
                    SynergyTierLevel.NONE -> null
                }
            },
            researchedSets = StandardSets.researched(),
            groundedSets = StandardSets.grounded(),
            pickModelActive = net != null,
            modelSets = pickNetRepo.bundledSets(StandardSets.codes, format),
        )
    }

    // Deck-fit probe for pick scoring: re-projects per candidate against the already-computed
    // [before] projection. Null when there's no projection to compare against (lane still open).
    private fun deckFitProbe(
        pool: List<com.firstpick.cards.RankedCard>,
        before: DeckOption?,
    ): ((com.firstpick.cards.RankedCard) -> DeckProjector.Fit?)? {
        if (before == null) return null
        val strength = archetypeRepo.strengthMap()
        return { card ->
            DeckProjector.fit(pool, card, repo.setMetrics, metaRepo::meta, archetypeRepo::archetypeRating, strength, synergyRepo.index, before)
        }
    }

    // Pool copies that fell out of [proj]'s 23 (and its nonbasic lands) — the flip side of
    // "Deck so far": what your picks brought home but the current best build doesn't want.
    private fun cutsOf(pool: List<com.firstpick.cards.RankedCard>, proj: DeckOption?): List<DeckSpellUi> {
        if (proj == null) return emptyList()
        val inCounts = (proj.spells + proj.nonbasicLands).groupingBy { it.name }.eachCount()
        return pool.groupBy { it.name }
            .flatMap { (name, copies) -> copies.drop(inCounts[name] ?: 0) }
            .toDeckSpells()
    }

    private fun DeckOption.toUi(): DeckOptionUi {
        val nonbasicCount = nonbasicLands.size
        val basics = (landCount - nonbasicCount).coerceAtLeast(0)
        val landLine = buildString {
            append("$landCount lands")
            if (nonbasicCount > 0) append(" · $nonbasicCount nonbasic")
            append(" · Arena adds $basics basics")
        }
        return DeckOptionUi(
            colors = colors,
            basePair = basePair,
            splash = splash,
            theme = theme,
            tier = tier,
            type = type,
            outlook = outlook,
            power = powerScore.toInt(),
            creatures = creatures,
            removal = removal,
            landLine = landLine,
            spells = spells.toDeckSpells(),
            lands = nonbasicLands.toDeckSpells(),
        )
    }

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
        }.sortedWith(deckSpellOrder)

    private fun deckCardType(rating: com.firstpick.cards.CardRating?, meta: CardMeta?): String {
        val types = rating?.types.orEmpty()
        fun has(t: String) = types.any { it.contains(t, ignoreCase = true) }
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
            val origIdx = originalPackIds.withIndex()
                .firstOrNull { (idx, id) -> id == s.card.grpId && idx !in usedIndices }
                ?.index ?: 0
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
                modelRank = s.modelRank,
            )
        }
    }

    private fun dataErrorMessage(t: Throwable?, set: String): String =
        ratingsErrorMessage((t as? DataUnavailableException)?.reason, set)

    companion object {
        private const val TAG = "DraftViewModel"
        private const val TOTAL_PICKS = 45
        private val WUBRG_ORDER = listOf('W', 'U', 'B', 'R', 'G')
    }
}

internal fun ratingsErrorMessage(reason: FetchFailure?, set: String): String = when (reason) {
    FetchFailure.RATE_LIMITED -> "17Lands is rate-limiting — retrying; using cached data if available"
    FetchFailure.OFFLINE -> "Can't reach 17Lands — check your connection"
    FetchFailure.SERVER_ERROR -> "17Lands is having issues — retrying shortly"
    FetchFailure.NOT_FOUND -> "No 17Lands data for $set yet"
    FetchFailure.BAD_DATA -> "17Lands returned unexpected data for $set"
    null -> "Couldn't load 17Lands data for $set"
}
