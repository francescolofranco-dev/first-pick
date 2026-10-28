package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverlayHealthTest {
    private val active = OverlayHealth(OverlayHealthState.ACTIVE, "Synced")

    @Test
    fun resolverPrioritizesConnectionWindowSafetyAndForeground() {
        assertEquals(
            OverlayHealthState.WAITING_FOR_ARENA,
            resolveOverlayHealth(
                arenaAvailable = false,
                arenaFrontmost = false,
                clickThroughFailed = true,
                work = active,
            ).state,
        )
        assertEquals(
            OverlayHealthState.CLICK_THROUGH_FAILED,
            resolveOverlayHealth(
                arenaAvailable = true,
                arenaFrontmost = true,
                clickThroughFailed = true,
                work = active,
            ).state,
        )
        assertEquals(
            OverlayHealthState.INACTIVE,
            resolveOverlayHealth(
                arenaAvailable = true,
                arenaFrontmost = false,
                clickThroughFailed = false,
                work = active,
            ).state,
        )
        assertEquals(
            active,
            resolveOverlayHealth(
                arenaAvailable = true,
                arenaFrontmost = false,
                clickThroughFailed = false,
                work = active,
                allowInBackground = true,
            ),
        )
    }

    @Test
    fun memoryFailuresDistinguishPermissionBuildAndTransientRead() {
        assertEquals(OverlayHealthState.ACCESS_REQUIRED, memoryReadHealth(
            ArenaMemoryRead.Unavailable("Grant Arena access", MemoryUnavailableKind.ACCESS_REQUIRED)).state)
        assertEquals(OverlayHealthState.UNSUPPORTED_LAYOUT, memoryReadHealth(
            ArenaMemoryRead.Unavailable("Unknown build", MemoryUnavailableKind.UNSUPPORTED)).state)
        assertEquals(OverlayHealthState.READING, memoryReadHealth(ArenaMemoryRead.Unavailable("Updating")).state)
        assertEquals(OverlayHealthState.READING, memoryReadHealth(ArenaMemoryRead.Paused("Close settings")).state)
        assertEquals(OverlayHealthState.INACTIVE, memoryReadHealth(ArenaMemoryRead.Paused("Different screen", true)).state)
    }

    @Test
    fun onlyActionableFailuresNeedAttention() {
        assertFalse(active.needsAttention)
        assertFalse(OverlayHealth(OverlayHealthState.READING).needsAttention)
        assertTrue(OverlayHealth(OverlayHealthState.ACCESS_REQUIRED).needsAttention)
        assertTrue(OverlayHealth(OverlayHealthState.UNSUPPORTED_LAYOUT).needsAttention)
        assertTrue(OverlayHealth(OverlayHealthState.CLICK_THROUGH_FAILED).needsAttention)
    }
}
