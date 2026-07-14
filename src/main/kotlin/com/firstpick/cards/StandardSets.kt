package com.firstpick.cards

import com.firstpick.core.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class StandardSet(val code: String, val rotatesYear: Int? = null, val tier: String = "data")

enum class SynergyTierLevel { RESEARCHED, DATA, NONE }

@Serializable
data class StandardManifest(
    val checked: String = "",
    val note: String = "",
    val sets: List<StandardSet> = emptyList(),
)


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

    fun tier(setCode: String): SynergyTierLevel =
        when (manifest.sets.firstOrNull { it.code.equals(setCode, ignoreCase = true) }?.tier) {
            "researched" -> SynergyTierLevel.RESEARCHED
            "data" -> SynergyTierLevel.DATA
            else -> SynergyTierLevel.NONE
        }


    fun researched(): List<String> = manifest.sets.filter { it.tier == "researched" }.map { it.code.uppercase() }
    fun grounded(): List<String> = manifest.sets.filter { it.tier != "researched" }.map { it.code.uppercase() }

    private const val TAG = "StandardSets"
}
