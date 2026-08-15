package com.firstpick.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Stable states exposed by the Arena overlays to the main application. */
enum class OverlayHealthState(val label: String) {
    INACTIVE("Inactive"),
    WAITING_FOR_ARENA("Waiting for Arena"),
    READING("Reading Arena"),
    ACTIVE("Synced"),
    CAPTURE_UNAVAILABLE("Capture unavailable"),
    UNSUPPORTED_LAYOUT("Unsupported layout"),
    CLICK_THROUGH_FAILED("Click-through failed"),
}

/**
 * Current operational health of an Arena overlay.
 *
 * [state] is intentionally small and stable for UI branching. [detail] is a
 * concise, user-facing explanation that may be shown alongside its label.
 */
data class OverlayHealth(
    val state: OverlayHealthState,
    val detail: String = state.label,
) {
    val needsAttention: Boolean
        get() = state == OverlayHealthState.CAPTURE_UNAVAILABLE ||
            state == OverlayHealthState.UNSUPPORTED_LAYOUT ||
            state == OverlayHealthState.CLICK_THROUGH_FAILED
}

internal fun resolveOverlayHealth(
    arenaAvailable: Boolean,
    arenaFrontmost: Boolean,
    clickThroughFailed: Boolean,
    work: OverlayHealth,
    allowInBackground: Boolean = false,
): OverlayHealth = when {
    !arenaAvailable -> OverlayHealth(
        OverlayHealthState.WAITING_FOR_ARENA,
        "Open MTG Arena to connect the overlay.",
    )

    clickThroughFailed -> OverlayHealth(
        OverlayHealthState.CLICK_THROUGH_FAILED,
        "The overlay was hidden because it could not pass clicks through to Arena.",
    )

    !arenaFrontmost && !allowInBackground -> OverlayHealth(
        OverlayHealthState.INACTIVE,
        "Arena is open but not in the foreground.",
    )

    else -> work
}

/** Emits only on state changes and reports a final inactive state on disposal. */
@Composable
internal fun OverlayHealthEffect(
    health: OverlayHealth,
    onHealthChanged: (OverlayHealth) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onHealthChanged)
    LaunchedEffect(health) {
        currentCallback(health)
    }
    DisposableEffect(Unit) {
        onDispose {
            currentCallback(
                OverlayHealth(
                    OverlayHealthState.INACTIVE,
                    "Overlay is turned off.",
                ),
            )
        }
    }
}
