package com.firstpick.stats

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Extracts only structured performance records; authentication and chat are never persisted. */
class ActivityLogParser(initialTime: Instant = Instant.now()) {
    private val json = Json { ignoreUnknownKeys = true }
    private var at = initialTime
    private data class Player(val seat: Int, val team: Int, val course: String?, val event: String)
    private data class Room(
        val players: List<Player>, var localSeat: Int? = null, var startingSeat: Int? = null,
        var result: JsonArray? = null, var at: String = "", var games: Int = 1, var currentGame: Int? = null,
    )
    private val rooms = linkedMapOf<String, Room>()
    private var activeMatch: String? = null

    fun onLine(line: String): List<ActivityRecord> {
        Regex("^<(\\d{8} \\d{2}:\\d{2}:\\d{2})>").find(line)?.let {
            runCatching { LocalDateTime.parse(it.groupValues[1], DateTimeFormatter.ofPattern("ddMMyyyy HH:mm:ss"))
                .atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()?.let { time -> at = time }
        }
        if ('{' !in line || "==>" in line) return emptyList()
        if (!listOf("CourseId", "limitedClass", "constructedClass", "matchGameRoomStateChangedEvent", "greToClientEvent").any { it in line }) return emptyList()
        val root = runCatching { json.parseToJsonElement(line.substring(line.indexOf('{'))) as? JsonObject }.getOrNull() ?: return emptyList()
        root.str("timestamp")?.let { stamp ->
            runCatching { stamp.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(stamp) }.getOrNull()?.let { at = it }
        }
        val output = mutableListOf<ActivityRecord>()
        val timestamp = at.toString()
        val courses = root.arr("Courses")?.mapNotNull { it as? JsonObject }.orEmpty() +
            listOfNotNull(root.obj("Course"), root.takeIf { it.str("CourseId") != null })
        for (course in courses) {
            val id = course.str("CourseId") ?: continue
            val event = course.str("InternalEventName") ?: course.str("EventName") ?: continue
            val hasResults = course.int("CurrentWins") != null || course.int("CurrentLosses") != null
            val pool = course.arr("CardPool")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
            val deck = course.obj("CourseDeck")?.arr("MainDeck")?.mapNotNull { element ->
                val c = element as? JsonObject ?: return@mapNotNull null
                val card = c.int("cardId") ?: return@mapNotNull null
                val count = c.int("quantity") ?: return@mapNotNull null
                (card to count).takeIf { card > 0 && count in 1..250 }
            }?.toMap().orEmpty()
            output += CourseRecord(id, timestamp, event,
                if (hasResults) course.int("CurrentWins") ?: 0 else null,
                if (hasResults) course.int("CurrentLosses") ?: 0 else null,
                course.str("CurrentModule").equals("Complete", true), pool, deck,
                draftComplete = course.str("CurrentModule") in listOf("DeckSelect", "DeckBuilder", "CreateMatch", "Complete"))
        }
        for (ladder in listOf("limited", "constructed")) {
            val rank = root.str("${ladder}Class") ?: continue
            val season = root.int("${ladder}SeasonOrdinal") ?: continue
            val tier = root.int("${ladder}Level") ?: 0
            val wins = root.int("${ladder}MatchesWon") ?: 0
            val losses = root.int("${ladder}MatchesLost") ?: 0
            val draws = root.int("${ladder}MatchesDrawn") ?: 0
            output += RankRecord(digest("$ladder|$season|$rank|$tier|$wins|$losses|$draws"), timestamp,
                ladder, season, rank, tier, wins, losses, draws)
        }
        root.obj("matchGameRoomStateChangedEvent")?.obj("gameRoomInfo")?.let { info ->
            val config = info.obj("gameRoomConfig")
            val id = config?.str("matchId") ?: info.obj("finalMatchResult")?.str("matchId") ?: activeMatch
            if (id != null) {
                val players = config?.arr("reservedPlayers")?.mapNotNull { element ->
                    val p = element as? JsonObject ?: return@mapNotNull null
                    val seat = p.int("systemSeatId") ?: return@mapNotNull null
                    val team = p.int("teamId") ?: return@mapNotNull null
                    Player(seat, team, p.str("courseId"), p.str("eventId").orEmpty())
                }.orEmpty()
                if (players.isNotEmpty()) { rooms.getOrPut(id) { Room(players) }; activeMatch = id }
                val final = info.obj("finalMatchResult")
                if (final != null) rooms[id]?.apply {
                    result = final.arr("resultList"); this.at = timestamp
                    games = result?.count { (it as? JsonObject)?.str("scope") == "MatchScope_Game" }?.coerceAtLeast(1) ?: 1
                }
                rooms[id]?.toRecord(id)?.let(output::add)
            }
        }
        root.obj("greToClientEvent")?.arr("greToClientMessages")?.forEach { element ->
            val message = element as? JsonObject ?: return@forEach
            val state = message.obj("gameStateMessage") ?: return@forEach
            val game = state.obj("gameInfo")
            val id = game?.str("matchID") ?: activeMatch ?: return@forEach
            val room = rooms[id] ?: return@forEach
            message.arr("systemSeatIds")?.singleOrNull()?.let { (it as? JsonPrimitive)?.intOrNull }?.let { room.localSeat = it }
            game?.int("gameNumber")?.let { room.currentGame = it }
            val turn = state.obj("turnInfo")
            if (room.currentGame == 1 && turn?.int("turnNumber") == 1 && room.startingSeat == null) room.startingSeat = turn.int("activePlayer")
            if (game?.str("matchState") == "MatchState_MatchComplete") {
                room.result = game.arr("results"); room.at = timestamp
                room.games = game.int("gameNumber") ?: 1
            }
            room.toRecord(id)?.let(output::add)
        }
        while (rooms.size > 128) rooms.remove(rooms.keys.first())
        return output
    }

    private fun Room.toRecord(id: String): MatchRecord? {
        val player = players.firstOrNull { it.seat == localSeat } ?: return null
        val final = result?.mapNotNull { it as? JsonObject }?.firstOrNull { it.str("scope") == "MatchScope_Match" } ?: return null
        val outcome = when (final.str("result")) {
            "ResultType_Draw" -> "Draw"
            "ResultType_WinLoss" -> final.int("winningTeamId")?.let { if (it == player.team) "Win" else "Loss" }
            else -> null
        } ?: return null
        if (player.event.isBlank()) return null
        return MatchRecord(id, at, player.event, player.course, outcome, startingSeat?.let { it == player.seat }, games)
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
