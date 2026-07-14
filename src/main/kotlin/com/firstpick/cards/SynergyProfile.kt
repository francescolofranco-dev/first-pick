package com.firstpick.cards

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class SynergyMechanic(val name: String, val summary: String = "")

@Serializable
data class SynergyArchetype(
    val pair: String,
    val name: String,
    val playstyle: String = "",
    val speed: String = "",
    val signposts: List<String> = emptyList(),
    val payoffs: List<String> = emptyList(),
    val enablers: List<String> = emptyList(),
    val keyCards: List<String> = emptyList(),
)

@Serializable
data class SynergyCombo(val cards: List<String>, val note: String = "")

@Serializable
data class SetSynergyProfile(
    val set: String,
    val setName: String = "",
    val generated: String = "",
    val mechanics: List<SynergyMechanic> = emptyList(),
    val archetypes: List<SynergyArchetype> = emptyList(),
    val combos: List<SynergyCombo> = emptyList(),
)

enum class SynergyRole { SIGNPOST, PAYOFF, ENABLER, KEY }

data class SynergyTag(val pair: String, val role: SynergyRole, val archetypeName: String)

data class ComboPartner(val name: String, val note: String)


class SynergyIndex(val profile: SetSynergyProfile) {
    private val tagsByName: Map<String, List<SynergyTag>>
    private val partnersByName: Map<String, Map<String, ComboPartner>>
    private val archetypesByPair: Map<String, SynergyArchetype>

    init {
        val tags = mutableMapOf<String, MutableList<SynergyTag>>()
        for (arch in profile.archetypes) {


            fun addAll(names: List<String>, role: SynergyRole) {
                for (n in names) {
                    val list = tags.getOrPut(normalize(n)) { mutableListOf() }
                    if (list.none { it.pair == arch.pair }) list.add(SynergyTag(arch.pair, role, arch.name))
                }
            }
            addAll(arch.signposts, SynergyRole.SIGNPOST)
            addAll(arch.payoffs, SynergyRole.PAYOFF)
            addAll(arch.enablers, SynergyRole.ENABLER)
            addAll(arch.keyCards, SynergyRole.KEY)
        }
        tagsByName = tags

        val partners = mutableMapOf<String, MutableMap<String, ComboPartner>>()
        for (combo in profile.combos) {
            for (a in combo.cards) for (b in combo.cards) {
                if (normalize(a) == normalize(b)) continue
                partners.getOrPut(normalize(a)) { mutableMapOf() }[normalize(b)] = ComboPartner(b, combo.note)
            }
        }
        partnersByName = partners

        archetypesByPair = profile.archetypes.associateBy { it.pair }
    }

    fun tags(name: String): List<SynergyTag> = tagsByName[normalize(name)].orEmpty()


    fun partners(name: String): Map<String, ComboPartner> = partnersByName[normalize(name)].orEmpty()

    fun archetype(pair: String): SynergyArchetype? = archetypesByPair[pair]

    companion object {
        fun normalize(name: String): String = name.lowercase().substringBefore(" //").trim()
    }
}


class SynergyRepository(private val cacheDir: Path = AppPaths.cacheDir) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mutex = Mutex()

    @Volatile
    var index: SynergyIndex? = null
        private set

    @Volatile
    var loadedSet: String? = null
        private set

    suspend fun load(setCode: String) {
        val key = setCode.uppercase()
        if (key == loadedSet) return
        mutex.withLock {
            if (key == loadedSet) return
            val profile = withContext(Dispatchers.IO) { loadProfile(key) }
            index = profile?.let(::SynergyIndex)
            loadedSet = key
            Log.info(TAG, "synergy profile for $key: ${profile?.archetypes?.size ?: 0} archetypes")
        }
    }

    private fun loadProfile(key: String): SetSynergyProfile? {
        val override = cacheDir.resolve("synergy_$key.json")
        if (Files.exists(override)) {
            val fromOverride = parse(runCatching { Files.readString(override) }.getOrNull(), key)
            if (fromOverride != null) return fromOverride
            Log.warn(TAG, "override synergy_$key.json unusable — falling back to bundled profile")
        }
        val bundled = javaClass.getResourceAsStream("/synergy/$key.json")
            ?.use { it.readBytes().decodeToString() }
        return parse(bundled, key)
    }

    private fun parse(body: String?, key: String): SetSynergyProfile? {
        if (body == null) return null
        return runCatching { json.decodeFromString<SetSynergyProfile>(body) }
            .onFailure { Log.warn(TAG, "bad synergy profile for $key: $it") }
            .getOrNull()
            ?.takeIf { it.set.equals(key, ignoreCase = true) }
    }

    internal fun indexProfile(profile: SetSynergyProfile) {
        index = SynergyIndex(profile)
        loadedSet = profile.set.uppercase()
    }

    companion object {
        private const val TAG = "Synergy"
    }
}
