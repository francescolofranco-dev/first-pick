package com.firstpick.draft

import com.firstpick.core.AppPaths
import com.firstpick.core.PlayerDatabase
import com.firstpick.model.DraftState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Serializable
data class DraftHistoryEntry(val id: String, val completedAt: String, val state: DraftState)

/** Saves before the tracker can advance to another draft, including during log replay. */
class DraftHistory(
    private val file: Path = AppPaths.appSupport.resolve("draft-history.json"),
    private val database: PlayerDatabase = PlayerDatabase(file.resolveSibling("firstpick.db")),
) {
    private val json = database.json
    private val entries = MutableStateFlow(load())

    private fun load(): List<DraftHistoryEntry> {
        val saved = database.read("draft").map { json.decodeFromString<DraftHistoryEntry>(it) }
        if (!Files.exists(file) || database.read("history_migration").isNotEmpty()) return saved
        // Retain the legacy file as a recovery copy. Existing database entries take precedence.
        val legacy = json.decodeFromString<List<DraftHistoryEntry>>(Files.readString(file))
        val known = saved.map { it.id }.toSet()
        val missing = legacy.filterNot { it.id in known }
        database.putAll("draft", missing.map { Triple(it.id, it.completedAt, json.encodeToString(it)) })
        database.put("history_migration", "legacy-json", Instant.now().toString(), "{\"complete\":true}")
        return (saved + missing).sortedByDescending { Instant.parse(it.completedAt) }
    }
    val history = entries.asStateFlow()

    @Synchronized
    fun record(state: DraftState) = record(state, Instant.now().toString())

    @Synchronized
    fun record(state: DraftState, completedAt: String) {
        if (state.phase != com.firstpick.model.DraftPhase.COMPLETE || state.pool.isEmpty()) return
        val existing = entries.value.firstOrNull {
            val old = it.state
            when {
                state.courseId != null && old.courseId != null -> state.courseId == old.courseId
                state.draftId != null && old.draftId != null -> state.draftId == old.draftId
                else -> state.eventName == old.eventName && state.pool.groupingBy { id -> id }.eachCount() ==
                    old.pool.groupingBy { id -> id }.eachCount()
            }
        }
        val entry = DraftHistoryEntry(existing?.id ?: UUID.randomUUID().toString(),
            existing?.completedAt ?: completedAt, state)
        if (existing == entry) return
        val updated = (entries.value.filterNot { it.id == entry.id } + entry).sortedByDescending { Instant.parse(it.completedAt) }
        database.put("draft", entry.id, entry.completedAt, json.encodeToString(entry))
        entries.value = updated
    }
}
