package com.firstpick.stats

import com.firstpick.core.PlayerDatabase
import com.firstpick.draft.DraftHistory
import com.firstpick.draft.DraftHistoryEntry
import com.firstpick.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.time.Instant
import kotlin.test.*

class PlayerStatsTest {
    @Test fun recoveredResultsPersistAndReplayWithoutDuplicatingOrLosingProvenance() = runBlocking {
        val dir = Files.createTempDirectory("fp-recovered")
        val db = PlayerDatabase(dir.resolve("stats.db"))
        val file = dir.resolve("recovery.json")
        val at = "2026-09-22T00:00:00Z"
        val source = "https://mtga.untapped.gg/profile/test/deck/draft"
        val course = CourseRecord("untapped:draft", at, "PickTwoDraft_HOB", 4, 1, true,
            colors = "BG", sourceUrl = source, historyId = "saved-draft")
        val match = MatchRecord("untapped:replay", at, course.event, course.id, "Win", false,
            sourceUrl = "https://mtga.untapped.gg/replay/replay", draftDateOnly = true)
        Files.writeString(file, db.json.encodeToString(RecoveredDraftResults(listOf(course), listOf(match))))
        val repo = PlayerStatsRepository(db)
        repo.importRecoveredResults(file); repo.importRecoveredResults(file)
        repo.importLogs(emptyList())
        Files.delete(file)
        val restored = PlayerStatsRepository(PlayerDatabase(db.path)).state.value
        assertEquals(listOf(course), restored.courses)
        assertEquals(listOf(match), restored.matches)
        assertEquals(ResultSummary(4, 1), restored.courses.courseResults())
        assertTrue(restored.courses.single().isTrophy())
    }

    @Test fun recoveredImportValidatesAllRowsBeforeWriting() {
        val dir = Files.createTempDirectory("fp-invalid-recovery")
        val db = PlayerDatabase(dir.resolve("stats.db"))
        val file = dir.resolve("recovery.json")
        val course = CourseRecord("untapped:a", "2026-09-22T00:00:00Z", "PickTwoDraft_HOB", 1, 2,
            sourceUrl = "https://mtga.untapped.gg/profile/test/deck/a")
        val wrong = MatchRecord("untapped:bad", course.at, course.event, "missing", "Win",
            sourceUrl = "https://mtga.untapped.gg/replay/bad")
        Files.writeString(file, db.json.encodeToString(RecoveredDraftResults(listOf(course), listOf(wrong))))
        assertFailsWith<IllegalArgumentException> { PlayerStatsRepository(db).importRecoveredResults(file) }
        assertTrue(db.read("course").isEmpty())
        assertTrue(db.read("match").isEmpty())
    }

    @Test fun trophyTargetsFollowDraftTypeAndRequireCompletion() {
        fun course(event: String, wins: Int, complete: Boolean = true) =
            CourseRecord("a", "2026-09-22T00:00:00Z", event, wins, 0, complete)
        assertTrue(course("PickTwoDraft_HOB", 4).isTrophy())
        assertFalse(course("PickTwoDraft_HOB", 3).isTrophy())
        assertFalse(course("PickTwoDraft_HOB", 4, false).isTrophy())
        assertTrue(course("TraditionalDraft_HOB", 3).isTrophy())
        assertFalse(course("QuickDraft_HOB", 4).isTrophy())
        assertTrue(course("PremierDraft_HOB", 7).isTrophy())
    }

    @Test fun draftScopeExcludesOtherModesFromPreviouslySavedRecords() {
        val db = PlayerDatabase(Files.createTempDirectory("fp-draft-scope").resolve("stats.db"))
        val at = "2026-10-21T10:00:00Z"
        val events = listOf("PremierDraft_DSK", "QuickDraft_DSK", "PickTwoDraft_FRA", "TraditionalDraft_SOS",
            "Y26_FRA_Bot_Draft_20261017", "Cube", "Sealed_DSK", "Traditional_Ladder", "Historic_Play", "MWM_Standard")
        for ((index, event) in events.withIndex()) {
            db.put("course", "c$index", at, db.json.encodeToString(CourseRecord("c$index", at, event, 2, 1)))
            db.put("match", "m$index", at, db.json.encodeToString(MatchRecord("m$index", at, event, outcome = "Win")))
        }
        for (ladder in listOf("limited", "constructed")) {
            db.put("rank", ladder, at, db.json.encodeToString(RankRecord(ladder, at, ladder, 94, "Gold", 2, 2, 1)))
        }
        val visible = PlayerStatsRepository(db).state.value
        assertEquals("Cube Draft", eventType("Cube"))
        assertEquals(events.take(6).toSet(), visible.courses.map { it.event }.toSet())
        assertEquals(events.take(6).toSet(), visible.matches.map { it.event }.toSet())
        assertEquals(ResultSummary(12, 6), visible.courses.courseResults())
        assertEquals(ResultSummary(6, 0), visible.matches.results())
        assertEquals(listOf("limited"), visible.ranks.map { it.ladder })
        // Scope changes preserve older database records, while keeping them out of all UI summaries.
        assertEquals(10, db.read("course").size)
        assertEquals(10, db.read("match").size)
    }

    @Test fun newNormalGameplayAndConstructedRanksAreNotSaved() {
        val db = PlayerDatabase(Files.createTempDirectory("fp-draft-live").resolve("stats.db"))
        val repo = PlayerStatsRepository(db)
        repo.onLine("""{"Courses":[{"CourseId":"play","InternalEventName":"Historic_Play","CurrentWins":8,"CurrentLosses":2},{"CourseId":"sealed","InternalEventName":"Sealed_DSK","CurrentWins":5,"CurrentLosses":3},{"CourseId":"draft","InternalEventName":"QuickDraft_DSK","CurrentWins":3,"CurrentLosses":1}]}""")
        repo.onLine("""{"constructedSeasonOrdinal":94,"constructedClass":"Gold","constructedLevel":2,"constructedMatchesWon":12,"constructedMatchesLost":9}""")
        assertEquals(listOf("QuickDraft_DSK"), repo.state.value.courses.map { it.event })
        assertEquals(1, db.read("course").size)
        assertTrue(db.read("rank").isEmpty())
    }

    @Test fun legacyDraftHistoryMigratesWithoutChangingItsIdsDatesOrCards() {
        val dir = Files.createTempDirectory("fp-migrate")
        val file = dir.resolve("draft-history.json")
        val database = PlayerDatabase(dir.resolve("firstpick.db"))
        val state = DraftState(phase = DraftPhase.COMPLETE, setCode = "DSK", pool = listOf(7, 7, 9),
            seen = mapOf((1 to 1) to listOf(7, 9)), savedDeck = SavedDeck(mapOf(7 to 2), mapOf(9 to 1)))
        val entry = DraftHistoryEntry("old-id", "2026-01-02T03:04:05Z", state)
        Files.writeString(file, database.json.encodeToString(listOf(entry)))
        val original = Files.readString(file)
        assertEquals(listOf(entry), DraftHistory(file, database).history.value)
        assertEquals(original, Files.readString(file))
        assertEquals(1, DraftHistory(file, database).history.value.size)
        Files.delete(file) // Database continues to work when the legacy copy is unavailable.
        assertEquals(listOf(entry), DraftHistory(file, PlayerDatabase(database.path)).history.value)
    }

    @Test fun repeatedArchivesAndCourseUpdatesNeverDoubleCountOrLoseResults() = runBlocking {
        val dir = Files.createTempDirectory("fp-stats")
        val file = dir.resolve("arena.log")
        Files.writeString(file, """
            {"Courses":[{"CourseId":"draft-a","InternalEventName":"PremierDraft_DSK_20261001","CurrentModule":"CreateMatch","CurrentWins":3,"CurrentLosses":2}]}
            {"Course":{"CourseId":"draft-a","InternalEventName":"PremierDraft_DSK_20261001","CurrentModule":"Complete","CurrentWins":5,"CurrentLosses":3}}
            {"limitedSeasonOrdinal":94,"limitedClass":"Gold","limitedLevel":2,"limitedMatchesWon":10,"limitedMatchesLost":7}
        """.trimIndent())
        val db = PlayerDatabase(dir.resolve("stats.db"))
        val repo = PlayerStatsRepository(db)
        repo.importLogs(listOf(file))
        repo.importLogs(listOf(file))
        Files.delete(file) // The records remain available after Arena rotates/deletes this source.
        val restarted = PlayerStatsRepository(PlayerDatabase(db.path))
        val course = restarted.state.value.courses.single()
        assertEquals(5, course.wins)
        assertEquals(3, course.losses)
        assertTrue(course.complete)
        assertEquals(1, restarted.state.value.ranks.size)
        restarted.onLine("""{"Course":{"CourseId":"draft-a","InternalEventName":"PremierDraft_DSK_20261001","CurrentModule":"DeckSelect"}}""")
        assertEquals(ResultSummary(5, 3), restarted.state.value.courses.courseResults())
        assertTrue(restarted.state.value.courses.single().complete)
    }

    @Test fun matchResultsUseTheLocalTeamAndAreCountedOncePerMatch() {
        val repo = PlayerStatsRepository(PlayerDatabase(Files.createTempDirectory("fp-match").resolve("stats.db")))
        val room = """{"timestamp":"1792595546179","matchGameRoomStateChangedEvent":{"gameRoomInfo":{"gameRoomConfig":{"matchId":"match-a","reservedPlayers":[{"systemSeatId":1,"teamId":9,"courseId":"opponent","eventId":"PremierDraft_DSK"},{"systemSeatId":2,"teamId":7,"courseId":"ours","eventId":"PremierDraft_DSK"}]}}}}"""
        val first = """{"greToClientEvent":{"greToClientMessages":[{"systemSeatIds":[2],"gameStateMessage":{"gameInfo":{"matchID":"match-a","gameNumber":1},"turnInfo":{"turnNumber":1,"activePlayer":1}}}]}}"""
        val gameOnly = """{"greToClientEvent":{"greToClientMessages":[{"systemSeatIds":[2],"gameStateMessage":{"gameInfo":{"matchID":"match-a","gameNumber":1,"results":[{"scope":"MatchScope_Game","result":"ResultType_WinLoss","winningTeamId":7}]}}}]}}"""
        val final = """{"matchGameRoomStateChangedEvent":{"gameRoomInfo":{"gameRoomConfig":{"matchId":"match-a"},"finalMatchResult":{"resultList":[{"scope":"MatchScope_Game","result":"ResultType_WinLoss","winningTeamId":9},{"scope":"MatchScope_Game","result":"ResultType_WinLoss","winningTeamId":7},{"scope":"MatchScope_Game","result":"ResultType_WinLoss","winningTeamId":7},{"scope":"MatchScope_Match","result":"ResultType_WinLoss","winningTeamId":7}]}}}}"""
        repo.onLine(room); repo.onLine(first); repo.onLine(gameOnly)
        assertTrue(repo.state.value.matches.isEmpty())
        repo.onLine(final); repo.onLine(final)
        val match = repo.state.value.matches.single()
        assertEquals("Win", match.outcome)
        assertEquals("ours", match.courseId)
        assertEquals(false, match.onPlay)
        assertEquals(3, match.games)
        assertEquals(ResultSummary(1, 0), repo.state.value.matches.results())
    }

    @Test fun unknownResultsAndStartingPlayersAreNotGuessed() {
        val parser = ActivityLogParser(Instant.parse("2026-01-01T00:00:00Z"))
        assertTrue(parser.onLine("not JSON").isEmpty())
        assertTrue(parser.onLine("""{"token":"secret","playerName":"private"}""").isEmpty())
        val course = parser.onLine("""{"Course":{"CourseId":"a","InternalEventName":"QuickDraft_DSK","CurrentModule":"DeckSelect","CardPool":[1,2]}}""").single() as CourseRecord
        assertNull(course.wins); assertNull(course.losses)
        assertNull(ResultSummary().winRate)
        assertEquals(0.5, ResultSummary(2, 2, 1).winRate)
    }

    @Test fun recoveringInventoryDoesNotTreatAnUnfinishedFortyCardPoolAsComplete() = runBlocking {
        val dir = Files.createTempDirectory("fp-course")
        val db = PlayerDatabase(dir.resolve("stats.db"))
        val history = DraftHistory(dir.resolve("history.json"), db)
        val repo = PlayerStatsRepository(db, history)
        val pool = (1..40).joinToString(",")
        repo.onLine("""{"CourseId":"a","InternalEventName":"QuickDraft_DSK","CurrentModule":"BotDraft","CardPool":[$pool]}""")
        assertTrue(history.history.value.isEmpty())
        repo.onLine("""{"CourseId":"a","InternalEventName":"QuickDraft_DSK","CurrentModule":"DeckSelect","CardPool":[$pool]}""")
        assertEquals(1, history.history.value.size)
        assertEquals(DraftPhase.COMPLETE, history.history.value.single().state.phase)
        repo.onLine("""{"CourseId":"a","InternalEventName":"QuickDraft_DSK","CurrentModule":"DeckSelect","CardPool":[$pool]}""")
        assertEquals(1, history.history.value.size)
    }
}
