package com.firstpick.overlay

import kotlin.test.*

class MemoryOverlayPresentationTest {
    @Test fun waitingForRecommendationsDoesNotAlternateWithTheFreshReadWatchdog() {
        val presenter = MemoryOverlayPresentation<String>()
        val reason = OverlayHealth(OverlayHealthState.READING, "Waiting for recommendations for Arena's complete current pack.")
        presenter.clear(0, reason)
        assertEquals(reason, presenter.expire(100).health)
        assertEquals(reason, presenter.expire(1500).health)
    }
    private val rect = NormalizedRect(.1, .2, .12, .3)
    private val geometry = ArenaCardGeometry(2560, 1440, listOf(ArenaVisibleCard(1, 1, ArenaCardZone.PACK, rect)))

    @Test fun isolatedMissAndPositionUpdatesNeverBlankVerifiedAdvice() {
        val presenter = MemoryOverlayPresentation<String>()
        presenter.accepted("pack", "editor", "Advice", geometry, 0)
        val missed = presenter.interrupted(ArenaMemoryRead.Paused("A card is moving"), 150)
        assertEquals("Advice", missed.content)
        assertEquals(geometry, missed.geometry)
        val moved = geometry.copy(cards = geometry.cards.map { it.copy(bounds = rect.copy(x = .3)) })
        val updated = presenter.accepted("pack", "editor", "Advice", moved, 300)
        assertEquals("Advice", updated.content)
        assertEquals(.3, updated.geometry!!.cards.single().bounds.x)
        assertEquals(OverlayHealthState.ACTIVE, updated.health.state)
    }

    @Test fun stalePositionsExpireEvenWhenSemanticReadingsContinue() {
        val presenter = MemoryOverlayPresentation<String>()
        presenter.accepted("pack", "editor", "Advice", geometry, 0)
        assertNotNull(presenter.accepted("pack", "editor", "Advice", null, 600).geometry)
        val stale = presenter.accepted("pack", "editor", "Advice", null, 1300)
        assertEquals("Advice", stale.content)
        assertNull(stale.geometry)
        assertEquals(OverlayHealthState.READING, stale.health.state)
    }

    @Test fun transientHoverGeometryFailureKeepsVerifiedPositionsUntilRecovery() {
        val presenter = MemoryOverlayPresentation<String>()
        presenter.accepted("pack", "editor", "Advice", geometry, 0)
        val missing = presenter.accepted("pack", "editor", "Advice", null, 100)
        assertEquals("Advice", missing.content)
        assertEquals(geometry, missing.geometry)
        assertEquals(geometry, presenter.accepted("pack", "editor", "Advice", geometry, 200).geometry)
    }

    @Test fun actualPackAndEditorChangesCannotCarryOldScores() {
        val presenter = MemoryOverlayPresentation<String>()
        presenter.accepted("old", "editor", "Old scores", geometry, 0)
        assertNull(presenter.settling("new", "editor", geometry, 150, newPack = true).content)
        presenter.accepted("old", "editor", "Old scores", geometry, 300)
        assertNull(presenter.settling("old", "different-editor", geometry, 450).content)
        presenter.accepted("new", "different-editor", "New scores", geometry, 600)
        assertNull(presenter.interrupted(ArenaMemoryRead.Paused("New pack", resetPresentation = true), 650).content)
    }

    @Test fun oneReadFailureIsRetainedButSustainedFailureAndClosedArenaAreNot() {
        val presenter = MemoryOverlayPresentation<String>()
        presenter.accepted("pack", "editor", "Advice", geometry, 0)
        assertNotNull(presenter.interrupted(ArenaMemoryRead.Unavailable("Temporary read gap"), 200).content)
        assertNull(presenter.expire(1300).content)
        presenter.accepted("pack", "editor", "Advice", geometry, 1500)
        assertNull(presenter.interrupted(ArenaMemoryRead.Unavailable("Arena closed", resetPresentation = true), 1550).content)
    }

    @Test fun repeatedHoverInterruptionsDoNotBlinkAndRecoveryIsImmediate() {
        val presenter = MemoryOverlayPresentation<String>()
        presenter.accepted("pack", "editor", "Advice", geometry, 0)
        for (time in 100L..10_000L step 100) {
            val frame = if (time % 300 == 0L) presenter.interrupted(ArenaMemoryRead.Paused("Hover animation"), time)
                else presenter.accepted("pack", "editor", "Advice", geometry, time)
            assertEquals("Advice", frame.content)
            assertNotNull(frame.geometry)
        }
    }
}
