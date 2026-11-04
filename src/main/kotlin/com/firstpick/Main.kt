package com.firstpick

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.firstpick.ui.DevFlags
import com.firstpick.overlay.ArenaDeckBuilderOverlay
import com.firstpick.overlay.ArenaMemoryReader
import com.firstpick.overlay.ArenaOverlayTracker
import com.firstpick.overlay.DeckCardCount
import com.firstpick.overlay.OverlayCard
import com.firstpick.overlay.OverlayHealth
import com.firstpick.overlay.OverlayHealthState
import com.firstpick.model.DraftPhase
import com.firstpick.core.AppPaths
import com.firstpick.ui.App
import com.firstpick.ui.DeckOptionUi
import com.firstpick.ui.DeckSpellUi
import com.firstpick.ui.DraftViewModel
import com.firstpick.ui.FirstPickAboutMenu
import com.firstpick.ui.FirstPickAboutWindow
import com.firstpick.ui.PackCardUi
import com.firstpick.ui.deckBuildKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.nio.file.Path

fun main() {
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    }
    AppPaths.ensureDirectories()

    val logPath = System.getenv("FIRSTPICK_LOG")?.let(Path::of) ?: AppPaths.defaultPlayerLog

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = com.firstpick.core.PlayerDatabase()
    val draftHistory = com.firstpick.draft.DraftHistory(database = database)
    val viewModel = DraftViewModel(appScope, logPath = logPath,
        draftHistory = draftHistory,
        statsRepository = com.firstpick.stats.PlayerStatsRepository(database, draftHistory))
    viewModel.start()

    application {
        if (System.getenv("FIRSTPICK_SMOKE") == "1") {
            LaunchedEffect(Unit) {
                delay(4000)
                appScope.cancel()
                exitApplication()
            }
        }

        val liveState by viewModel.ui.collectAsState()
        var archivedGuidance by remember { mutableStateOf<com.firstpick.ui.DraftUiState?>(null) }
        // An explicit history action can resume guidance after Arena rotates its log.
        // A newly observed draft always takes over from that archived context.
        LaunchedEffect(liveState.phase, liveState.sessionKey) {
            if (liveState.phase == DraftPhase.DRAFTING) archivedGuidance = null
        }
        val state = if (liveState.phase == DraftPhase.DRAFTING) liveState else archivedGuidance ?: liveState
        val history by viewModel.history.collectAsState()
        val stats by viewModel.stats.collectAsState()
        val liveDeckReader = remember { ArenaMemoryReader() }
        val liveDeckAccess by liveDeckReader.access.collectAsState()
        DisposableEffect(liveDeckReader) {
            onDispose { Thread({ liveDeckReader.close() }, "live-deck-close").apply { isDaemon = true }.start() }
        }
        var isAboutOpen by remember { mutableStateOf(false) }
        var isOverlayOpen by remember { mutableStateOf(false) }
        var draftOverlayHealth by remember {
            mutableStateOf(OverlayHealth(OverlayHealthState.INACTIVE, "Overlay is turned off."))
        }
        var deckOverlayHealth by remember {
            mutableStateOf(OverlayHealth(OverlayHealthState.INACTIVE, "Deck guidance is not active."))
        }
        var liveDeckGuidance by remember(state.sessionKey) { mutableStateOf<com.firstpick.overlay.DeckGuidanceOverlayModel?>(null) }
        var committedDeck by remember(state.sessionKey, state.phase == DraftPhase.COMPLETE) {
            mutableStateOf<DeckOptionUi?>(null)
        }
        val selectedDeck = committedDeck ?: state.deckOptions.firstOrNull()
        val activeOverlayHealth = when {
            state.phase == DraftPhase.COMPLETE && isOverlayOpen && selectedDeck != null -> deckOverlayHealth
            state.phase != DraftPhase.COMPLETE && isOverlayOpen -> draftOverlayHealth
            else -> OverlayHealth(OverlayHealthState.INACTIVE, "Overlay is turned off.")
        }

        val grantArenaAccess: () -> Unit = { appScope.launch(Dispatchers.IO) { liveDeckReader.grantAccess() }; Unit }

        FirstPickAboutMenu(onOpenAbout = { isAboutOpen = true })

        Window(
            onCloseRequest = {
                appScope.cancel()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(720.dp, 760.dp)),
            title = "FirstPick",
        ) {
            LaunchedEffect(window) {
                window.minimumSize = Dimension(600, 640)
                com.firstpick.ui.MacOverlay.configureDarkWindow(window)
            }
            App(
                state = state,
                history = history,
                stats = stats,
                onReviewDraft = viewModel::reviewDraft,
                onGuideArchivedDraft = { archived ->
                    archivedGuidance = archived
                    committedDeck = null
                    isOverlayOpen = true
                },
                isOverlayOpen = isOverlayOpen,
                overlayHealth = activeOverlayHealth,
                liveDeckGuidance = liveDeckGuidance,
                onToggleOverlay = { isOverlayOpen = !isOverlayOpen },
                liveDeckAccess = liveDeckAccess,
                onGrantArenaAccess = grantArenaAccess,
                committedDeck = selectedDeck,
                onUseDeck = {
                    if (state.phase == DraftPhase.COMPLETE) {
                        liveDeckGuidance = null
                        committedDeck = it
                    }
                },
                onSelectPickCount = viewModel::setPickCountOverride,
                onSimulate = { viewModel.startSimulation(it) },
                onStopSim = { viewModel.stopSimulation() },
                onTogglePause = { viewModel.toggleSimulationPause() },
                onLoadReferenceSet = viewModel::loadReferenceCards,
                onRetryRatings = { viewModel.retryRatings() },
                onNewDraft = {
                    archivedGuidance = null
                    committedDeck = null
                    viewModel.newDraft()
                },
            )
        }

        if (isAboutOpen) {
            FirstPickAboutWindow(onCloseRequest = { isAboutOpen = false })
        }

        when {
            state.phase == DraftPhase.COMPLETE && isOverlayOpen && selectedDeck != null -> {
                val target = remember(selectedDeck) { deckGuidanceTarget(selectedDeck) }
                val draftPool = remember(state.draftPool) { deckGuidancePool(state.draftPool) }
                ArenaDeckBuilderOverlay(
                    target = target,
                    draftPool = draftPool,
                    sessionKey = state.sessionKey,
                    memoryReader = liveDeckReader,
                    onGrantArenaAccess = grantArenaAccess,
                    onHealthChanged = { deckOverlayHealth = it },
                    onGuidanceChanged = { liveDeckGuidance = it },
                )
            }

            isOverlayOpen && (state.phase == DraftPhase.DRAFTING || state.phase == DraftPhase.IDLE) -> {
                val cards = remember(state.packCards, state.picksPerChoice) {
                    overlayCards(state.packCards, state.picksPerChoice)
                }
                ArenaOverlayTracker(
                    cards = cards,
                    draftPool = deckGuidancePool(state.draftPool),
                    sessionKey = "${state.sessionKey}:${state.pack}:${state.pick}",
                    memoryReader = liveDeckReader,
                    onGrantArenaAccess = grantArenaAccess,
                    onHealthChanged = { draftOverlayHealth = it },
                )
            }

        }
    }
}

internal fun overlayCards(packCards: List<PackCardUi>, picksPerChoice: Int = 1): List<OverlayCard> {
    val highlights = com.firstpick.ui.packHighlights(packCards, picksPerChoice)
    return packCards.indices.sortedBy { packCards[it].originalIndex }.map { index ->
        val it = packCards[index]
        OverlayCard(
            value = it.value,
            highlight = highlights[index],
            imageUrl = it.imageUrl,
            name = it.name,
            originalIndex = it.originalIndex,
            isRoom = it.isRoom,
            recommendedPair = it.recommendedPair,
            grpId = it.grpId,
            breakdown = it.breakdown,
            gihWr = it.gihWr,
            model = it.breakdown?.takeIf { b -> b.modelShift != 0.0 && it.modelRank != null }?.let { b ->
                com.firstpick.ui.ModelExplain(it.modelRank!!, packCards.size, it.value?.minus(b.modelShift))
            },
        )
    }
}

internal fun deckGuidanceTarget(deck: DeckOptionUi): List<DeckCardCount> =
    deckGuidancePool(deck.spells + deck.lands)

internal fun deckGuidancePool(cards: List<DeckSpellUi>): List<DeckCardCount> = cards
    .filter { it.count > 0 }
    .map { DeckCardCount(it.name, it.count, isBasicLand = it.isBasicLand) }
