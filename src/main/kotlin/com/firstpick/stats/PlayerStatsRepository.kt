package com.firstpick.stats

import com.firstpick.core.AppPaths
import com.firstpick.core.PlayerDatabase
import com.firstpick.draft.DraftHistory
import com.firstpick.model.DraftPhase
import com.firstpick.model.DraftState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class PlayerStatsRepository(private val database: PlayerDatabase, private val draftHistory: DraftHistory? = null) {
    private val json = database.json
    private val cardColors by lazy {
        val result = mutableMapOf<Int, String>()
        if (Files.isDirectory(AppPaths.cacheDir)) Files.list(AppPaths.cacheDir).use { files ->
            files.filter { it.fileName.toString().matches(Regex("ratings[23]_[A-Z0-9]+_PremierDraft\\.json")) }.forEach { path ->
                runCatching {
                    val root = json.parseToJsonElement(Files.readString(path)) as? kotlinx.serialization.json.JsonObject
                    root?.arr("data")?.forEach { value ->
                        val card = value as? kotlinx.serialization.json.JsonObject ?: return@forEach
                        card.int("mtga_id")?.let { id -> card.str("color")?.let { result[id] = it } }
                    }
                }
            }
        }
        result
    }

    private fun deckColors(deck: Map<Int, Int>): String? {
        val nonbasics = deck.keys.filterNot { it in 75021..75025 }
        if (nonbasics.isEmpty() || nonbasics.any { it !in cardColors }) return null
        return "WUBRG".filter { color -> nonbasics.any { color in cardColors[it].orEmpty() } }.ifBlank { "Colorless" }
    }
    private val courses = database.read("course").map { json.decodeFromString<CourseRecord>(it) }.associateBy { it.id }.toMutableMap()
    private val matches = database.read("match").map { json.decodeFromString<MatchRecord>(it) }.associateBy { it.id }.toMutableMap()
    private val ranks = database.read("rank").map { json.decodeFromString<RankRecord>(it) }.associateBy { it.id }.toMutableMap()
    private val _state = MutableStateFlow(snapshot())
    val state = _state.asStateFlow()
    private var liveParser = ActivityLogParser()

    private fun snapshot(importing: Boolean = false, error: String? = null) = PlayerStats(
        courses.values.sortedByDescending { it.at }, matches.values.sortedByDescending { it.at },
        ranks.values.sortedByDescending { it.at }, importing, error,
    )

    @Synchronized
    fun onLine(line: String) {
        var changed = false
        for (record in liveParser.onLine(line)) {
            changed = merge(record) || changed
            importDraft(record, draftHistory)
        }
        if (changed) _state.value = snapshot(error = _state.value.error)
    }

    private fun merge(record: ActivityRecord): Boolean {
        val updated: ActivityRecord = when (record) {
            is CourseRecord -> {
                val old = courses[record.id]
                record.copy(at = old?.at?.let { minOf(it, record.at) } ?: record.at,
                    wins = listOfNotNull(old?.wins, record.wins).maxOrNull(),
                    losses = listOfNotNull(old?.losses, record.losses).maxOrNull(),
                    complete = old?.complete == true || record.complete,
                    draftComplete = old?.draftComplete == true || record.draftComplete,
                    pool = if (record.pool.size >= (old?.pool?.size ?: 0)) record.pool else old!!.pool,
                    deck = record.deck.ifEmpty { old?.deck.orEmpty() },
                    colors = deckColors(record.deck.ifEmpty { old?.deck.orEmpty() }) ?: old?.colors)
            }
            is MatchRecord -> record.copy(onPlay = record.onPlay ?: matches[record.id]?.onPlay)
            is RankRecord -> ranks[record.id]?.let { if (it.at <= record.at) it else record } ?: record
        }
        val (kind, payload) = when (updated) {
            is CourseRecord -> {
                if (courses[updated.id] == updated) return false
                "course" to json.encodeToString(updated)
            }
            is MatchRecord -> {
                if (matches[updated.id] == updated) return false
                "match" to json.encodeToString(updated)
            }
            is RankRecord -> {
                if (ranks[updated.id] == updated) return false
                "rank" to json.encodeToString(updated)
            }
        }
        database.put(kind, updated.id, updated.at, payload)
        when (updated) {
            is CourseRecord -> courses[updated.id] = updated
            is MatchRecord -> matches[updated.id] = updated
            is RankRecord -> ranks[updated.id] = updated
        }
        return true
    }

    /** Replay each session with its own context. Stable Arena IDs prevent duplicate results. */
    suspend fun importLogs(paths: List<Path> = localLogFiles(), history: DraftHistory? = draftHistory) {
        _state.value = snapshot(importing = true)
        val failures = mutableListOf<String>()
        try {
            for (path in paths) {
                currentCoroutineContext().ensureActive()
                val parser = ActivityLogParser(sourceTime(path))
                runCatching {
                    Files.newBufferedReader(path).useLines { lines ->
                        for (line in lines) {
                            for (record in parser.onLine(line)) {
                                synchronized(this) { merge(record) }
                                importDraft(record, history)
                            }
                        }
                    }
                }.onFailure { failure ->
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    com.firstpick.core.Log.warn("PlayerStats", "Could not import ${path.fileName}", failure)
                    failures += path.fileName.toString()
                }
            }
        } finally {
            synchronized(this) {
                liveParser = ActivityLogParser()
                _state.value = snapshot(error = failures.takeIf { it.isNotEmpty() }?.let {
                    "Some logs could not be read (${it.size}). Saved records are still available."
                })
            }
        }
    }

    private fun importDraft(record: ActivityRecord, history: DraftHistory?) {
        if (history == null || record !is CourseRecord || !record.draftComplete || record.pool.size < 40) return
        val format = com.firstpick.model.DraftFormat.fromEventName(record.event)
        if (format == com.firstpick.model.DraftFormat.UNKNOWN) return
        val known = history.history.value.any {
            it.state.courseId == record.id || it.state.pool.sorted() == record.pool.sorted()
        }
        if (!known) history.record(DraftState(
            phase = DraftPhase.COMPLETE, format = format,
            setCode = com.firstpick.model.setCodeFromEventName(record.event),
            eventName = record.event, courseId = record.id, pool = record.pool,
            poolOrderKnown = false, pack = 3, pick = 15,
        ), record.at)
    }

    companion object {
        fun localLogFiles(): List<Path> {
            val home = Path.of(System.getProperty("user.home"))
            val directories = listOf(AppPaths.defaultPlayerLog.parent,
                home.resolve("Library/Application Support/com.wizards.mtga/Logs/Logs"),
                home.resolve("Library/Application Support/MTGA_Draft_Tool/Logs"))
            return directories.flatMap { dir ->
                if (!Files.isDirectory(dir)) emptyList() else Files.list(dir).use { files ->
                    files.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".log") }.toList()
                }
            }.distinct().sortedBy { sourceTime(it) }
        }

        private fun sourceTime(path: Path): Instant {
            val timestamp = Regex("UTC_Log - (\\d{2}-\\d{2}-\\d{4} \\d{2}\\.\\d{2}\\.\\d{2})").find(path.fileName.toString())
            return timestamp?.let {
                LocalDateTime.parse(it.groupValues[1], DateTimeFormatter.ofPattern("MM-dd-yyyy HH.mm.ss")).toInstant(ZoneOffset.UTC)
            } ?: Files.getLastModifiedTime(path).toInstant()
        }
    }
}
