package com.firstpick.cards

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class BasicLandIdentity(
    val name: String,
    val imageUrl: String? = null,
)

fun interface BasicLandResolver {
    fun resolve(grpId: Int): BasicLandIdentity?
}

/** Resolves unrated basics from Arena's installed card database. */
class ArenaBasicLandResolver(
    private val rawDataDir: Path = AppPaths.arenaRawDataDir,
    private val sqliteExecutable: Path = Path.of("/usr/bin/sqlite3"),
) : BasicLandResolver {
    private data class DatabaseSnapshot(val path: Path, val modifiedAt: Long, val size: Long)

    private val hits = ConcurrentHashMap<Int, BasicLandIdentity>()
    private val misses = ConcurrentHashMap<Int, DatabaseSnapshot>()

    override fun resolve(grpId: Int): BasicLandIdentity? {
        hits[grpId]?.let { return it }
        val database = newestDatabase()
        if (database != null && misses[grpId] == database) return null

        val name = database?.let { queryBasicLandName(it.path, grpId) }
        val identity = name
            ?.let(::canonicalBasicLandName)
            ?.let {
                BasicLandIdentity(
                    name = it,
                    imageUrl = "https://api.scryfall.com/cards/arena/$grpId?format=image&version=normal",
                )
            }
        if (identity != null) {
            hits[grpId] = identity
            misses.remove(grpId)
        } else if (database != null) {
            misses[grpId] = database
        }
        return identity
    }

    private fun newestDatabase(): DatabaseSnapshot? = runCatching {
        if (!Files.isDirectory(rawDataDir) || !Files.isExecutable(sqliteExecutable)) return@runCatching null
        Files.list(rawDataDir).use { files ->
            files
                .filter { path ->
                    val name = path.fileName.toString()
                    Files.isRegularFile(path) && name.startsWith(DATABASE_PREFIX) && name.endsWith(DATABASE_SUFFIX)
                }
                .max(Comparator.comparingLong { path ->
                    runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(Long.MIN_VALUE)
                })
                .orElse(null)
                ?.let { path ->
                    DatabaseSnapshot(
                        path = path,
                        modifiedAt = Files.getLastModifiedTime(path).toMillis(),
                        size = Files.size(path),
                    )
                }
        }
    }.onFailure { Log.warn(TAG, "couldn't locate Arena's card database: $it") }.getOrNull()

    private fun queryBasicLandName(database: Path, grpId: Int): String? = runCatching {
        val sql = """
            SELECT title.Loc
            FROM Cards card
            JOIN Localizations_enUS title ON title.LocId = card.TitleId AND title.Formatted = 1
            JOIN Localizations_enUS type ON type.LocId = card.TypeTextId AND type.Formatted = 1
            WHERE card.GrpId = $grpId AND type.Loc LIKE 'Basic%Land%'
            LIMIT 1;
        """.trimIndent()
        val process = ProcessBuilder(
            sqliteExecutable.toString(),
            "-readonly",
            "-noheader",
            database.toString(),
            sql,
        ).redirectErrorStream(true).start()
        if (!process.waitFor(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) return@runCatching null
        process.inputStream.bufferedReader().use { it.readLine()?.trim() }?.takeIf { it.isNotEmpty() }
    }.onFailure { Log.warn(TAG, "Arena card lookup failed for $grpId: $it") }.getOrNull()

    private companion object {
        const val TAG = "ArenaCards"
        const val DATABASE_PREFIX = "Raw_CardDatabase_"
        const val DATABASE_SUFFIX = ".mtga"
        const val LOOKUP_TIMEOUT_SECONDS = 2L
    }
}

internal fun isBasicLandName(name: String): Boolean {
    return canonicalBasicLandName(name) != null
}

internal fun canonicalBasicLandName(name: String): String? {
    val cleaned = name.replace(HTML_TAG, "").trim()
    val withoutAlchemyPrefix = cleaned.removeLeadingIgnoreCase("A-")
    val snowCovered = withoutAlchemyPrefix.startsWith("Snow-Covered ", ignoreCase = true)
    val normalized = withoutAlchemyPrefix.removeLeadingIgnoreCase("Snow-Covered ").trim()
    val canonical = BASIC_LAND_NAMES.firstOrNull { it.equals(normalized, ignoreCase = true) } ?: return null
    return if (snowCovered) "Snow-Covered $canonical" else canonical
}

private fun String.removeLeadingIgnoreCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

private val BASIC_LAND_NAMES = setOf("Plains", "Island", "Swamp", "Mountain", "Forest", "Wastes")
private val HTML_TAG = Regex("<[^>]+>")
