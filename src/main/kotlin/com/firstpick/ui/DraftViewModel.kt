package com.firstpick.ui

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.DeckBuilder
import com.firstpick.advisor.DeckOption
import com.firstpick.advisor.DeckProjector
import com.firstpick.advisor.Lane
import com.firstpick.advisor.LaneDetector
import com.firstpick.advisor.PickNetRanker
import com.firstpick.advisor.PoolNeeds
import com.firstpick.advisor.WUBRG
import com.firstpick.model.DraftPhase
import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMeta
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.cards.DraftRatingsFormats
import com.firstpick.cards.StandardSets
import com.firstpick.cards.SynergyRepository
import com.firstpick.cards.SynergyTierLevel
import com.firstpick.cards.DataUnavailableException
import com.firstpick.cards.FetchFailure
import com.firstpick.cards.RatingsDataSource
import com.firstpick.cards.SeventeenLandsClient
import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import com.firstpick.draft.DraftTracker
import com.firstpick.log.LogWatcher
import com.firstpick.model.DraftState
import com.firstpick.model.PickNetRepository
import com.firstpick.signals.SignalsEngine
import com.firstpick.sim.DraftSimulator
import com.firstpick.guide.LimitedPolicy
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
    private val references: com.firstpick.guide.SetReferenceRepository = com.firstpick.guide.SetReferenceRepository(),
) {
    private val _ui = MutableStateFlow(DraftUiState())
    val ui: StateFlow<DraftUiState> = _ui.asStateFlow()

    private val mutex = Mutex()
    private val ratingsLoadMutex = Mutex()
    private var requestedKey: String? = null
    @Volatile private var currentError: String? = null
    @Volatile private var ratingsLoading = false

    private var watcherJob: Job? = null
    private var simJob: Job? = null
    @Volatile private var simulating = false
    private val simPaused = MutableStateFlow(false)

    private var setReferences = emptyList<com.firstpick.guide.SetReference>()

    fun loadReferenceCards(code: String) {
        scope.launch(Dispatchers.IO) {
            val load = mutex.withLock {
                val set = setReferences.firstOrNull { it.code == code } ?: return@withLock false
                if (set.cardsLoaded || set.loadingCards) return@withLock false
                setReferences = setReferences.map { if (it.code == code) it.copy(loadingCards = true, cardError = false) else it }
                true
            }
            if (!load) return@launch
            publish()
            val result = runCatching { references.cards(code) }
            mutex.withLock {
                setReferences = setReferences.map { set ->
                    if (set.code != code) set else set.copy(
                        themes = result.getOrNull() ?: set.themes,
                        loadingCards = false, cardsLoaded = result.isSuccess, cardError = result.isFailure,
                    )
                }
            }
            publish()
        }
    }

    fun start() {
        scope.launch(Dispatchers.IO) {
            val sets = references.sets()
            mutex.withLock { setReferences = sets }
            publish()
        }
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
            tracker.consume(simulator.simulate(set, paused = simPaused, picksPerChoice = pickCountOverride.takeIf { it == 2 } ?: 1))
            if (tracker.state.value.setCode == null) {
                currentError = "Not enough identified Arena cards to simulate ${set.uppercase()}"
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

    /** Discard buffered/history lines as well as the old draft before following new activity. */
    fun newDraft() {
        if (simulating) return
        pickCountOverride = 0
        val previousWatcher = watcherJob
        previousWatcher?.cancel()
        watcherJob = scope.launch(Dispatchers.IO) {
            previousWatcher?.join()
            tracker.reset(keepEventContext = true)
            currentError = null
            publish()
            tracker.consume(watcher.lines(fromStart = false))
        }
    }

    @Volatile private var pickCountOverride = 0

    fun setPickCountOverride(count: Int) {
        require(count in 0..2)
        pickCountOverride = count
        scope.launch { publish() }
    }

    /** Force a new 17Lands request for the active set, even if this key was already attempted. */
    fun retryRatings() {
        scope.launch {
            val state = tracker.state.value
            val set = state.setCode ?: return@launch
            ensureLoaded(set, forceRefresh = true)
            ensureLanePair(tracker.state.value)
            publish()
        }
    }

    private suspend fun publish() = mutex.withLock {
        _ui.value = buildUi(tracker.state.value).copy(dataError = currentError)
    }

    private suspend fun ensureLoaded(set: String, forceRefresh: Boolean = false) {
        ratingsLoadMutex.withLock {
            val format = DraftRatingsFormats.DEFAULT
            val key = "${set.uppercase()}_$format"
            val proceed = mutex.withLock {
                if (!forceRefresh && key == requestedKey) {
                    false
                } else {
                    requestedKey = key
                    true
                }
            }
            if (!proceed) return@withLock

            currentError = null
            ratingsLoading = true
            publish()

            // Bundled guidance can be ready before live ratings and should never block P1P1.
            scope.launch {
                runCatching { synergyRepo.load(set) }
                publish()
            }

            val outcome = runCatching { repo.load(set, format, forceRefresh = forceRefresh) }
            ratingsLoading = false
            val failure = outcome.exceptionOrNull()
            currentError = failure?.let { dataErrorMessage(it, set) }
                ?: if (repo.isLoadedFor(set, format)) null else dataErrorMessage(null, set)

            val reason = (failure as? DataUnavailableException)?.reason
            if (reason != null && SeventeenLandsClient.isTransient(reason)) {
                // A later draft-state emission may try again; manual retry always bypasses this guard.
                mutex.withLock { if (requestedKey == key) requestedKey = null }
            }
            publish()

            if (failure == null) {
                scope.launch {
                    runCatching { metaRepo.load(set, repo.cardNames) }
                    runCatching { archetypeRepo.loadStrengths(set, format) }
                    runCatching { pickNetRepo.load(set, format) }
                    publish()
                }
            }
        }
    }

    private suspend fun ensureLanePair(state: DraftState) {
        val set = state.setCode ?: return
        val format = DraftRatingsFormats.DEFAULT
        val dataKey = "${set.uppercase()}_$format"
        if (!repo.isLoadedFor(set, format)) return
        val pool = state.pool.map(repo::resolve)
        val signals = SignalsEngine.openLanes(state.seen, repo::resolve)
        val strength = archetypeRepo.strengthMap().takeIf { archetypeRepo.loadedKey == dataKey }.orEmpty()
        val pair = LaneDetector.detect(
            pool,
            repo.setMetrics,
            strength,
            signals,
            metaRepo::meta,
            useRecency = state.poolOrderKnown,
        ).pair ?: return
        runCatching { archetypeRepo.ensurePair(set, format, pair) }
    }

    private fun buildUi(state: DraftState): DraftUiState {
        val override = pickCountOverride
        val picksPerChoice = override.takeIf { it != 0 } ?: state.picksPerChoice
        val format = DraftRatingsFormats.DEFAULT
        val dataKey = state.setCode?.let { "${it.uppercase()}_$format" }
        val loaded = state.setCode?.let { repo.isLoadedFor(it, format) } == true
        val ratingsInfo = repo.ratingsInfo?.takeIf { loaded }
        val ratingsStatus = when {
            state.setCode == null -> RatingsDataStatus.IDLE
            ratingsLoading -> RatingsDataStatus.LOADING
            ratingsInfo?.source == RatingsDataSource.NETWORK -> RatingsDataStatus.FRESH
            ratingsInfo?.source == RatingsDataSource.FRESH_CACHE -> RatingsDataStatus.CACHED
            ratingsInfo?.source == RatingsDataSource.STALE_CACHE -> RatingsDataStatus.STALE_CACHE
            ratingsInfo?.source == RatingsDataSource.CARD_CATALOG -> RatingsDataStatus.CARD_CATALOG
            currentError != null -> RatingsDataStatus.ERROR
            else -> RatingsDataStatus.IDLE
        }
        val archetypesLoaded = dataKey != null && archetypeRepo.loadedKey == dataKey
        val pairStrength = archetypeRepo.strengthMap().takeIf { archetypesLoaded }.orEmpty()
        val synergyLoaded = state.setCode != null && synergyRepo.loadedSet.equals(state.setCode, ignoreCase = true)
        val metaLoaded = state.setCode != null && metaRepo.loadedSet.equals(state.setCode, ignoreCase = true)
        val synergy = synergyRepo.index.takeIf { synergyLoaded }
        val meta: (String) -> CardMeta? = if (metaLoaded) metaRepo::meta else { _ -> null }
        val archetypeRating: (String, String) -> com.firstpick.cards.CardRating? = if (archetypesLoaded) {
            archetypeRepo::archetypeRating
        } else {
            { _, _ -> null }
        }
        val pool = if (loaded) state.pool.map(repo::resolve) else emptyList()
        val signals = if (loaded && picksPerChoice == 1) SignalsEngine.openLanes(state.seen, repo::resolve) else emptyMap()
        val lane = if (loaded) {
            LaneDetector.detect(
                pool,
                repo.setMetrics,
                pairStrength,
                signals,
                meta,
                useRecency = state.poolOrderKnown,
            )
        } else {
            Lane(emptySet(), null, emptyMap())
        }

        val poolMetas = pool.mapNotNull { meta(it.name) }
        val net = pickNetRepo.netFor(state.setCode, format)

        val liveProjection = if (loaded && lane.hasBaseColorEvidence) {
            DeckProjector.project(
                pool, repo.setMetrics, meta, archetypeRating, pairStrength, synergy,
                basePair = lane.pair ?: com.firstpick.advisor.canonicalPair(lane.colors),
            )
        } else {
            null
        }
        val activeManaSources = liveProjection?.takeIf { projection ->
            (lane.pair?.let { projection.basePair == it }
                ?: projection.basePair.toSet().containsAll(lane.colors)) &&
                projection.manaSources?.allRequirementsMet == true
        }?.manaSources
        val rows = if (loaded && state.packCards.isNotEmpty()) {
            val resolvedPack = repo.resolvePack(state.packCards)
            val basicLands = resolvedPack.filter { it.isBasicLand }
            val scored = advisor.score(
                pack = resolvedPack.filterNot { it.isBasicLand },
                pool = pool,
                packNumber = state.pack.coerceAtLeast(1),
                pickNumber = state.pick.coerceAtLeast(1),
                metrics = repo.setMetrics,
                lane = lane,
                archetypeRating = archetypeRating,
                meta = meta,
                synergy = synergy,
                deckFit = deckFitProbe(pool, liveProjection, pairStrength, synergy, meta, archetypeRating),
                activeManaSources = activeManaSources,
                picksPerChoice = picksPerChoice,
            )
            val ranked = net?.let { PickNetRanker.rerank(it, scored, pool.map { c -> c.name }) } ?: scored
            val pair = if (picksPerChoice == 2) {
                com.firstpick.advisor.PairAdvisor.recommend(ranked) { candidate, partner ->
                    advisor.score(
                        pack = listOf(candidate), pool = pool + listOfNotNull(partner),
                        packNumber = state.pack.coerceAtLeast(1), pickNumber = state.pick.coerceAtLeast(1),
                        metrics = repo.setMetrics, lane = lane, archetypeRating = archetypeRating,
                        meta = meta, synergy = synergy, activeManaSources = activeManaSources,
                        picksPerChoice = picksPerChoice,
                    ).single()
                }
            } else emptySet()
            packRows(ranked, basicLands, state.packCards).mapIndexed { index, row ->
                row.copy(recommendedPair = index in pair)
            }
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
                    meta = meta,
                    archetypeRating = archetypeRating,
                    pairStrength = pairStrength,
                    synergy = synergy,
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
            picksPerChoice = picksPerChoice,
            pickCountOverride = override,
            loadingRatings = ratingsLoading,
            dataError = null,
            ratingsDataStatus = ratingsStatus,
            ratingsDataWarning = when (ratingsInfo?.source) {
                RatingsDataSource.STALE_CACHE -> staleRatingsMessage(ratingsInfo.fallbackReason)
                RatingsDataSource.CARD_CATALOG -> "17Lands ratings are unavailable. Suggestions use card roles and researched synergies; retry as results become available."
                else -> null
            },
            ratingsLastUpdated = ratingsInfo?.lastUpdated,
            ratingsCardCount = ratingsInfo?.cardCount ?: 0,
            ratingsReliableCardCount = ratingsInfo?.reliableCardCount ?: 0,
            ratingsMedianGamesPerCard = ratingsInfo?.medianGamesPerCard ?: 0,
            canRetryRatings = ratingsStatus == RatingsDataStatus.ERROR ||
                ratingsStatus == RatingsDataStatus.STALE_CACHE || ratingsStatus == RatingsDataStatus.CARD_CATALOG,
            packCards = rows,
            laneColors = WUBRG_ORDER.filter { it in lane.colors },
            openLanes = openLanes,
            manaCurve = manaCurveOf(poolMetas),
            poolColorCounts = poolColorCounts(pool),
            poolCreatures = poolMetas.count { it.isCreature },
            poolNonCreatures = poolMetas.count { !it.isCreature && !it.isLand },
            lanePair = lane.pair,
            topPairs = lane.topPairs,
            archetypes = if (archetypesLoaded) archetypeRows(lane.pair) else emptyList(),
            deckNeeds = deckNeeds,
            deckNeedsAvailable = loaded && poolMetas.size == state.pool.size,
            deckOptions = deckOptions,
            setReferences = setReferences,
            draftPool = pool.toDeckSpells(),
            sessionKey = state.courseId ?: state.draftId ?: state.eventName,
            savedDeck = if (loaded) state.savedDeck?.let { saved ->
                val cards = saved.mainDeck.flatMap { (id, count) -> List(count) { repo.resolve(id) } }.toDeckSpells()
                // Unresolved IDs must not be presented as an exact saved-deck comparison.
                if (cards.any { it.name.startsWith("Unknown") || it.name.startsWith("Card #") }) null
                else com.firstpick.overlay.DeckSnapshot(
                    cards.map { com.firstpick.overlay.DeckCardCount(it.name, it.count, it.isBasicLand) },
                    saved.mainDeck.values.sum(),
                )
            } else null,
            deckSoFar = liveProjection?.toUi(),
            deckSoFarCuts = cutsOf(pool, liveProjection),
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


    private fun deckFitProbe(
        pool: List<com.firstpick.cards.RankedCard>,
        before: DeckOption?,
        pairStrength: Map<String, Double>,
        synergy: com.firstpick.cards.SynergyIndex?,
        meta: (String) -> CardMeta?,
        archetypeRating: (String, String) -> com.firstpick.cards.CardRating?,
    ): ((com.firstpick.cards.RankedCard) -> DeckProjector.Fit?)? {
        if (before == null) return null
        return { card ->
            DeckProjector.fit(pool, card, repo.setMetrics, meta, archetypeRating, pairStrength, synergy, before)
        }
    }


    private fun cutsOf(pool: List<com.firstpick.cards.RankedCard>, proj: DeckOption?): List<DeckSpellUi> {
        if (proj == null) return emptyList()
        val inCounts = (proj.spells + proj.nonbasicLands).groupingBy { it.name }.eachCount()
        return pool.groupBy { it.name }
            .flatMap { (name, copies) -> copies.drop(inCounts[name] ?: 0) }
            .toDeckSpells()
    }

    private fun DeckOption.toUi(): DeckOptionUi {
        val nonbasicCount = nonbasicLands.size
        val landLine = deckLandLine(landCount, nonbasicCount, manaSources?.basicSources)
        return DeckOptionUi(
            colors = colors,
            basePair = basePair,
            splash = splash,
            theme = theme,
            tier = tier,
            type = type,
            outlook = outlook,
            power = powerScore.toInt(),
            identityConfidence = identityConfidence,
            identityReasons = identityReasons,
            powerConfidence = powerConfidence,
            powerReasons = powerReasons,
            creatures = creatures,
            removal = removal,
            landLine = landLine,
            spells = spells.toDeckSpells(),
            lands = nonbasicLands.toDeckSpells(),
            hasObservedPower = hasObservedPower,
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
                imageUrl = c.imageUrl,
                typeLabel = if (c.isBasicLand) "Land" else deckCardType(c.rating, m),
                roles = deckCardRoles(m),
                isLand = c.isBasicLand || m?.isLand == true,
                isBasicLand = c.isBasicLand,
                manaCost = m?.manaCost,
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

    private fun deckCardRoles(meta: CardMeta?): List<String> {
        if (meta == null) return emptyList()
        return buildList {
            if (meta.isRemoval) add("Removal")
            if (meta.isCardDraw) add("Draw")
            if (meta.isEvasion) add("Evasion")
            if (meta.isFinisher) add("Finisher")
            if (meta.isFixing) add("Fixing")
        }
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

    private fun dataErrorMessage(t: Throwable?, set: String): String =
        ratingsErrorMessage((t as? DataUnavailableException)?.reason, set)

    companion object {
        private const val TAG = "DraftViewModel"
        private const val TOTAL_PICKS = LimitedPolicy.DRAFT_TOTAL_PICKS
        private val WUBRG_ORDER = listOf('W', 'U', 'B', 'R', 'G')
    }
}

internal fun deckLandLine(
    landCount: Int,
    nonbasicCount: Int,
    basicSources: Map<Char, Int>?,
): String = buildString {
    append("$landCount lands")
    val split = "WUBRG".mapNotNull { color ->
        basicSources?.get(color)?.takeIf { it > 0 }?.let { "$it$color" }
    }
    if (split.isNotEmpty()) append(" · ${split.joinToString(" · ")}")
    if (nonbasicCount > 0) append(" · $nonbasicCount nonbasic")
    if (split.isEmpty()) append(" · Arena adds ${(landCount - nonbasicCount).coerceAtLeast(0)} basics")
}

internal fun ratingsErrorMessage(reason: FetchFailure?, set: String): String = when (reason) {
    FetchFailure.RATE_LIMITED -> "17Lands is rate-limiting requests — use Retry data in a moment"
    FetchFailure.OFFLINE -> "Can't reach 17Lands — check your connection"
    FetchFailure.SERVER_ERROR -> "17Lands is having issues — use Retry data in a moment"
    FetchFailure.NOT_FOUND -> "No 17Lands data for $set yet"
    FetchFailure.BAD_DATA -> "17Lands returned unexpected data for $set"
    null -> "Couldn't load 17Lands data for $set"
}

internal fun staleRatingsMessage(reason: FetchFailure?): String = when (reason) {
    FetchFailure.RATE_LIMITED -> "Using saved ratings while 17Lands is rate-limiting requests"
    FetchFailure.OFFLINE -> "Using saved ratings while 17Lands is unreachable"
    FetchFailure.SERVER_ERROR -> "Using saved ratings while 17Lands is having issues"
    FetchFailure.NOT_FOUND -> "Using saved ratings because newer 17Lands data was not found"
    FetchFailure.BAD_DATA -> "Using saved ratings because newer 17Lands data was invalid"
    null -> "Using saved ratings because they could not be refreshed"
}
