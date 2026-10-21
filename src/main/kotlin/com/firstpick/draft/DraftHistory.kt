package com.firstpick.draft

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import com.firstpick.model.DraftState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

@Serializable
data class DraftHistoryEntry(val id: String, val completedAt: String, val state: DraftState)

/** Saves before the tracker can advance to another draft, including during log replay. */
class DraftHistory(private val file: Path = AppPaths.appSupport.resolve("draft-history.json")) {
    private val json = Json { ignoreUnknownKeys = true; allowStructuredMapKeys = true }
    private val entries = MutableStateFlow(runCatching {
        if (Files.exists(file)) json.decodeFromString<List<DraftHistoryEntry>>(Files.readString(file)) else emptyList()
    }.onFailure { Log.warn("DraftHistory", "Could not read draft history", it) }.getOrDefault(emptyList()))
    val history = entries.asStateFlow()

    @Synchronized
    fun record(state: DraftState) {
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
            existing?.completedAt ?: Instant.now().toString(), state)
        if (existing == entry) return
        val updated = (entries.value.filterNot { it.id == entry.id } + entry).sortedByDescending { Instant.parse(it.completedAt) }
        // Keep the previous archive intact if a write is interrupted.
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "draft-history-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(updated))
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            entries.value = updated
        } finally { Files.deleteIfExists(temporary) }
    }
}
