package com.firstpick

import androidx.compose.runtime.LaunchedEffect
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
import com.firstpick.overlay.ArenaOverlayTracker
import com.firstpick.overlay.DeckCardCount
import com.firstpick.overlay.OverlayCard
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
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path

fun main() {
    AppPaths.ensureDirectories()

    val logPath = System.getenv("FIRSTPICK_LOG")?.let(Path::of) ?: AppPaths.defaultPlayerLog

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val viewModel = DraftViewModel(appScope, logPath = logPath)
    viewModel.start()

    application {
        if (System.getenv("FIRSTPICK_SMOKE") == "1") {
            LaunchedEffect(Unit) {
                delay(4000)
                appScope.cancel()
                exitApplication()
            }
        }

        val state by viewModel.ui.collectAsState()
        var isAboutOpen by remember { mutableStateOf(false) }
        var isOverlayOpen by remember { mutableStateOf(false) }
        var committedDeckKey by remember(state.phase == DraftPhase.COMPLETE) {
            mutableStateOf<String?>(null)
        }
        val committedDeck: DeckOptionUi? = if (state.phase == DraftPhase.COMPLETE) {
            state.deckOptions.firstOrNull { it.deckBuildKey == committedDeckKey }
        } else {
            null
        }

        FirstPickAboutMenu(onOpenAbout = { isAboutOpen = true })

        Window(
            onCloseRequest = {
                appScope.cancel()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(720.dp, 760.dp)),
            title = "FirstPick",
        ) {
            App(
                state = state,
                isOverlayOpen = isOverlayOpen,
                onToggleOverlay = { isOverlayOpen = !isOverlayOpen },
                committedDeck = committedDeck,
                onUseDeck = {
                    if (state.phase == DraftPhase.COMPLETE) committedDeckKey = it.deckBuildKey
                },
                onStopGuidance = { committedDeckKey = null },
                onSelectFormat = { viewModel.setFormatChoice(it) },
                onSimulate = { viewModel.startSimulation(it) },
                onStopSim = { viewModel.stopSimulation() },
                onTogglePause = { viewModel.toggleSimulationPause() },
                onOpenGuideSource = ::openGuideSource,
            )
        }

        if (isAboutOpen) {
            FirstPickAboutWindow(onCloseRequest = { isAboutOpen = false })
        }

        when {
            state.phase == DraftPhase.COMPLETE && committedDeck != null -> {
                val target = remember(committedDeck) { deckGuidanceTarget(committedDeck) }
                val draftPool = remember(state.draftPool) { deckGuidancePool(state.draftPool) }
                ArenaDeckBuilderOverlay(target = target, draftPool = draftPool)
            }

            isOverlayOpen && (state.phase == DraftPhase.DRAFTING || state.phase == DraftPhase.IDLE) -> {
                val cards = remember(state.packCards) {
                    overlayCards(state.packCards)
                }
                ArenaOverlayTracker(cards = cards)
            }

            !isOverlayOpen && DevFlags.overlayTrack -> ArenaOverlayTracker()
        }
    }
}

internal fun guideSourceUri(url: String): URI? = runCatching { URI(url.trim()) }
    .getOrNull()
    ?.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() }

private fun openGuideSource(url: String) {
    val uri = guideSourceUri(url) ?: return
    runCatching {
        Desktop.getDesktop()
            .takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) }
            ?.browse(uri)
    }
}

internal fun overlayCards(packCards: List<PackCardUi>): List<OverlayCard> =
    packCards.sortedBy(PackCardUi::originalIndex).map {
        OverlayCard(
            value = it.value,
            imageUrl = it.imageUrl,
            name = it.name,
            originalIndex = it.originalIndex,
            isRoom = it.isRoom,
        )
    }

internal fun deckGuidanceTarget(deck: DeckOptionUi): List<DeckCardCount> =
    deckGuidancePool(deck.spells + deck.lands)

internal fun deckGuidancePool(cards: List<DeckSpellUi>): List<DeckCardCount> = cards
    .filter { it.count > 0 }
    .map { DeckCardCount(it.name, it.count, isBasicLand = it.isBasicLand) }
