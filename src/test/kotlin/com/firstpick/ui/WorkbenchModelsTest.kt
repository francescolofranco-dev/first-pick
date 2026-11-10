package com.firstpick.ui

import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.model.DraftState
import com.firstpick.stats.CourseRecord
import java.time.Instant
import java.time.YearMonth
import kotlin.test.*

class WorkbenchModelsTest {
    private val event = "PremierDraft_DSK"
    private fun spell(name: String, count: Int = 1, rate: Double? = .55) = DeckSpellUi(name, 2, "B", rate, count = count)
    private fun build(spells: List<DeckSpellUi>) = DeckOptionUi("BG", "BG", tier = "B", type = "Midrange", outlook = "Playable", power = 52,
        identityConfidence = "High", identityReasons = emptyList(), powerConfidence = "Medium", powerReasons = emptyList(), creatures = 12,
        removal = 3, landLine = "17 lands", spells = spells)

    @Test fun `close cuts subtract individual copies and exclude basic lands`() {
        val pool = listOf(spell("Fell", 2), spell("Fell"), spell("Weak", rate = null), spell("Swamp", 9).copy(isBasicLand = true))
        val cuts = closeCuts(pool, build(listOf(spell("Fell", 2))))
        assertEquals(listOf("Fell", "Weak"), cuts.map { it.name })
        assertEquals(1, cuts.first().count)
    }

    @Test fun `selected build consumes all copies without negative cuts`() {
        assertTrue(closeCuts(listOf(spell("Fell")), build(listOf(spell("Fell", 2)))).isEmpty())
    }

    @Test fun `set format and month are intersected rather than replacing each other`() {
        val filter = AnalyticsSelection("DSK", "Premier Draft", YearMonth.of(2026, 10))
        assertTrue(filter.includes("2026-10-15T12:00:00Z", event))
        assertFalse(filter.includes("2026-11-15T12:00:00Z", event))
        assertFalse(filter.includes("2026-10-15T12:00:00Z", "QuickDraft_DSK"))
        assertFalse(filter.includes("2026-10-15T12:00:00Z", "PremierDraft_BLB"))
        assertFalse(filter.includes("invalid", event))
    }

    @Test fun `rolling window excludes future and out of range records`() {
        val now = Instant.parse("2026-11-10T12:00:00Z")
        val filter = AnalyticsSelection(last30Days = true)
        assertTrue(filter.includes("2026-11-09T12:00:00Z", event, now = now))
        assertFalse(filter.includes("2026-10-01T12:00:00Z", event, now = now))
        assertFalse(filter.includes("2026-11-11T12:00:00Z", event, now = now))
    }

    @Test fun `finish distribution counts completed draft results only`() {
        val at = "2026-10-15T12:00:00Z"
        val c = CourseRecord("done", at, event, wins = 7, losses = 2, complete = true)
        val bins = finishDistribution(listOf(c, c.copy(id = "second", wins = 3), c.copy(id = "live", complete = false),
            c.copy(id = "unknown", losses = null), c.copy(id = "sealed", event = "Sealed_DSK")))
        assertEquals(2, bins.sum())
        assertEquals(1, bins[7]); assertEquals(1, bins[3])
    }

    @Test fun `historical joins use stable identifiers not event or dates`() {
        val entry = DraftHistoryEntry("saved", "2026-10-15T12:00:00Z", DraftState(courseId = "course"))
        val wrong = CourseRecord("unrelated", entry.completedAt, event)
        val exact = wrong.copy(id = "course")
        assertNull(courseForDraft(entry, listOf(wrong)))
        assertEquals(exact, courseForDraft(entry, listOf(wrong, exact)))
        assertEquals(wrong.copy(historyId = "saved"), courseForDraft(entry, listOf(exact, wrong.copy(historyId = "saved"))))
    }
}
