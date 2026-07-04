package com.firstpick.cards

import com.firstpick.core.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StandardSet(val code: String, val rotatesYear: Int? = null)

@Serializable
data class StandardManifest(
    val checked: String = "",
    val note: String = "",
    val sets: List<StandardSet> = emptyList(),
)

/**
 * The set of Standard-legal, Arena-drafted sets FirstPick ships synergy profiles for.
 * A set is supported while it is Standard-legal; when it rotates out, its profile and its
 * manifest entry are dropped (run `./gradlew auditSets` to find rotated sets).
 * This is advisory metadata — [SynergyRepository] still loads any bundled profile that exists.
 */
object StandardSets {
    private val json = Json { ignoreUnknownKeys = true }

    val manifest: StandardManifest by lazy {
        runCatching {
            javaClass.getResourceAsStream("/synergy/standard.json")
                ?.use { it.readBytes().decodeToString() }
                ?.let { json.decodeFromString<StandardManifest>(it) }
        }.onFailure { Log.warn(TAG, "standard manifest unreadable: $it") }
            .getOrNull() ?: StandardManifest()
    }

    val codes: Set<String> get() = manifest.sets.mapTo(mutableSetOf()) { it.code.uppercase() }

    fun isSupported(setCode: String): Boolean = setCode.uppercase() in codes

    private const val TAG = "StandardSets"
}
