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
import com.firstpick.guide.LimitedMode
import com.firstpick.guide.SetDraftGuideBuilder
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
    private val ratingsLoadMutex = Mutex()
    private var requestedKey: String? = null
    @Volatile private var currentError: String? = null
    @Volatile private var ratingsLoading = false

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
            val format = RatingsFormat.resolve(formatChoice, tracker.state.value.format)
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
        val format = RatingsFormat.resolve(formatChoice, state.format)
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
        val format = RatingsFormat.resolve(formatChoice, state.format)
        val constructionMode = LimitedMode.fromFormat(state.format)
        val dataKey = state.setCode?.let { "${it.uppercase()}_$format" }
        val loaded = state.setCode?.let { repo.isLoadedFor(it, format) } == true
        val ratingsInfo = repo.ratingsInfo?.takeIf { loaded }
        val ratingsStatus = when {
            state.setCode == null -> RatingsDataStatus.IDLE
            ratingsLoading -> RatingsDataStatus.LOADING
            ratingsInfo?.source == RatingsDataSource.NETWORK -> RatingsDataStatus.FRESH
            ratingsInfo?.source == RatingsDataSource.FRESH_CACHE -> RatingsDataStatus.CACHED
            ratingsInfo?.source == RatingsDataSource.STALE_CACHE -> RatingsDataStatus.STALE_CACHE
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
        val signals = if (loaded) SignalsEngine.openLanes(state.seen, repo::resolve) else emptyMap()
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

        val guideReady = state.setCode != null && (synergyLoaded || loaded)
        val setGuide = if (guideReady) {
            SetDraftGuideBuilder.build(
                setCode = state.setCode!!,
                dataFormat = format,
                ratings = if (loaded) repo.cardRatings else emptyList(),
                synergy = synergy,
                pairStrength = pairStrength,
            )
        } else {
            null
        }


        val liveProjection = if (loaded && lane.hasBaseColorEvidence) {
            DeckProjector.project(pool, repo.setMetrics, meta, archetypeRating, pairStrength, synergy, constructionMode)
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
                deckFit = deckFitProbe(pool, liveProjection, pairStrength, synergy, meta, archetypeRating, constructionMode),
                activeManaSources = activeManaSources,
            )
            val ranked = net?.let { PickNetRanker.rerank(it, scored, pool.map { c -> c.name }) } ?: scored
            packRows(ranked, basicLands, state.packCards)
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
                    mode = constructionMode,
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
            loadingRatings = ratingsLoading,
            dataError = null,
            ratingsDataStatus = ratingsStatus,
            ratingsDataWarning = ratingsInfo?.takeIf { it.source == RatingsDataSource.STALE_CACHE }
                ?.let { staleRatingsMessage(it.fallbackReason) },
            ratingsLastUpdated = ratingsInfo?.lastUpdated,
            ratingsCardCount = ratingsInfo?.cardCount ?: 0,
            ratingsReliableCardCount = ratingsInfo?.reliableCardCount ?: 0,
            ratingsMedianGamesPerCard = ratingsInfo?.medianGamesPerCard ?: 0,
            canRetryRatings = ratingsStatus == RatingsDataStatus.ERROR ||
                ratingsStatus == RatingsDataStatus.STALE_CACHE,
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
            deckOptions = deckOptions,
            setGuide = setGuide,
            guideLoading = state.setCode != null && setGuide == null,
            draftPool = pool.toDeckSpells(),
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


    private fun deckFitProbe(
        pool: List<com.firstpick.cards.RankedCard>,
        before: DeckOption?,
        pairStrength: Map<String, Double>,
        synergy: com.firstpick.cards.SynergyIndex?,
        meta: (String) -> CardMeta?,
        archetypeRating: (String, String) -> com.firstpick.cards.CardRating?,
        mode: LimitedMode,
    ): ((com.firstpick.cards.RankedCard) -> DeckProjector.Fit?)? {
        if (before == null) return null
        return { card ->
            DeckProjector.fit(pool, card, repo.setMetrics, meta, archetypeRating, pairStrength, synergy, before, mode)
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
                role = deckCardRole(m),
                isLand = c.isBasicLand || m?.isLand == true,
                isBasicLand = c.isBasicLand,
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

    private fun packRows(
        scored: List<ScoredCard>,
        basicLands: List<com.firstpick.cards.RankedCard>,
        originalPackIds: List<Int>,
    ): List<PackCardUi> {
        val ordered = scored.map { it.card to it } +
            basicLands.sortedBy { originalPackIds.indexOf(it.grpId) }.map { it to null }
        val usedIndices = mutableSetOf<Int>()
        return ordered.mapIndexed { i, (card, score) ->
            val origIdx = originalPackIds.withIndex()
                .firstOrNull { (idx, id) -> id == card.grpId && idx !in usedIndices }
                ?.index ?: 0
            usedIndices.add(origIdx)
            PackCardUi(
                grpId = card.grpId,
                originalIndex = origIdx,
                rank = i + 1,
                name = card.displayName,
                color = card.rating?.color.orEmpty(),
                rarity = card.rating?.rarity.orEmpty(),
                gihWr = card.gihWr,
                alsa = card.rating?.alsa,
                ata = card.rating?.ata,
                value = score?.value,
                isBomb = score?.isBomb == true,
                isRoom = card.rating?.types.orEmpty().any { type ->
                    type.contains("Room", ignoreCase = true)
                },
                isBasicLand = card.isBasicLand,
                reasons = score?.reasons ?: listOf("Basic land", "Not rated"),
                imageUrl = card.imageUrl,
                z = score?.z ?: 0.0,
                breakdown = score?.breakdown,
                modelRank = score?.modelRank,
            )
        }
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
