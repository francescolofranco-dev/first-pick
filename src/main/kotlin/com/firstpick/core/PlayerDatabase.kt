package com.firstpick.core

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/** Versioned local storage for drafts and the structured Arena records used by statistics. */
class PlayerDatabase(val path: Path = AppPaths.appSupport.resolve("firstpick.db")) {
    val json = Json { ignoreUnknownKeys = true; allowStructuredMapKeys = true }

    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        Class.forName("org.sqlite.JDBC")
        connection().use { db ->
            db.createStatement().use { sql ->
                val version = sql.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                require(version <= 1) { "This database was created by a newer FirstPick version." }
                sql.execute("PRAGMA journal_mode=WAL")
                sql.execute("CREATE TABLE IF NOT EXISTS records (kind TEXT NOT NULL, id TEXT NOT NULL, observed_at TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind,id))")
                sql.execute("CREATE INDEX IF NOT EXISTS records_by_date ON records(kind,observed_at)")
                sql.execute("PRAGMA user_version=1")
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").apply {
        createStatement().use { it.execute("PRAGMA busy_timeout=10000") }
    }

    @Synchronized
    fun read(kind: String): List<String> = connection().use { db ->
        db.prepareStatement("SELECT payload FROM records WHERE kind=? ORDER BY observed_at DESC, id").use { sql ->
            sql.setString(1, kind)
            sql.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
    }

    @Synchronized
    fun put(kind: String, id: String, at: String, payload: String) = putAll(kind, listOf(Triple(id, at, payload)))

    /** Imports and migrations either commit in full or leave the original database intact. */
    @Synchronized
    fun putAll(kind: String, records: List<Triple<String, String, String>>) {
        if (records.isEmpty()) return
        connection().use { db ->
            db.autoCommit = false
            try {
                db.prepareStatement("INSERT INTO records(kind,id,observed_at,payload) VALUES(?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET observed_at=excluded.observed_at,payload=excluded.payload").use { sql ->
                    for ((id, at, payload) in records) {
                        sql.setString(1, kind); sql.setString(2, id); sql.setString(3, at); sql.setString(4, payload)
                        sql.addBatch()
                    }
                    sql.executeBatch()
                }
                db.commit()
            } catch (failure: Exception) {
                db.rollback()
                throw failure
            }
        }
    }
}
