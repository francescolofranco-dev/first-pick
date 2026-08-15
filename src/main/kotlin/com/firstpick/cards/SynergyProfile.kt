package com.firstpick.cards

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

@Serializable
enum class SynergySourceKind {
    OFFICIAL_MECHANICS,
    OFFICIAL_LIMITED_GUIDE,
    OFFICIAL_RELEASE_NOTES,
    LIMITED_DATA,
}

@Serializable
data class SynergySource(
    val id: String,
    val title: String,
    val publisher: String,
    val author: String,
    /** Publication date, or the dataset snapshot date for a living data source. */
    val date: String,
    val url: String,
    val kind: SynergySourceKind,
)

@Serializable
data class SynergyMechanic(
    val name: String,
    val summary: String = "",
    val sourceIds: List<String> = emptyList(),
)

@Serializable
data class SynergyArchetype(
    val pair: String,
    val name: String,
    val playstyle: String = "",
    val speed: String = "",
    val sourceIds: List<String> = emptyList(),
    val signposts: List<String> = emptyList(),
    val payoffs: List<String> = emptyList(),
    val enablers: List<String> = emptyList(),
    val keyCards: List<String> = emptyList(),
)

@Serializable
data class SynergyCombo(
    val cards: List<String>,
    val note: String = "",
    val sourceIds: List<String> = emptyList(),
)

@Serializable
data class SetSynergyProfile(
    val schemaVersion: Int = 0,
    val set: String,
    val setName: String = "",
    val generated: String = "",
    val updatedAt: String = "",
    val sources: List<SynergySource> = emptyList(),
    val mechanics: List<SynergyMechanic> = emptyList(),
    val archetypes: List<SynergyArchetype> = emptyList(),
    val combos: List<SynergyCombo> = emptyList(),
)

/** Strict validation boundary for downloaded profile overrides and bundled profiles. */
object SynergyProfileValidator {
    const val CURRENT_SCHEMA_VERSION = 2
    private val SOURCE_ID = Regex("[a-z0-9][a-z0-9-]*")
    private val COLOR_PAIRS = setOf("WU", "WB", "WR", "WG", "UB", "UR", "UG", "BR", "BG", "RG")
    private val SPEEDS = setOf("aggro", "tempo", "midrange", "control", "ramp")

    fun errors(profile: SetSynergyProfile, expectedSet: String? = null): List<String> = buildList {
        if (profile.schemaVersion != CURRENT_SCHEMA_VERSION) {
            add("schemaVersion must be $CURRENT_SCHEMA_VERSION")
        }
        if (profile.set.isBlank()) add("set must not be blank")
        if (profile.setName.isBlank()) add("setName must not be blank")
        if (expectedSet != null && !profile.set.equals(expectedSet, ignoreCase = true)) {
            add("set '${profile.set}' does not match '$expectedSet'")
        }
        if (!profile.updatedAt.isIsoDate()) add("updatedAt must be an ISO-8601 date")
        if (profile.sources.isEmpty()) add("sources must not be empty")

        val sourceIds = mutableSetOf<String>()
        val sourceUrls = mutableSetOf<String>()
        for ((index, source) in profile.sources.withIndex()) {
            val label = "sources[$index]"
            if (!SOURCE_ID.matches(source.id)) add("$label.id is not a stable source id")
            if (!sourceIds.add(source.id)) add("duplicate source id '${source.id}'")
            if (source.title.isBlank()) add("$label.title must not be blank")
            if (source.publisher.isBlank()) add("$label.publisher must not be blank")
            if (source.author.isBlank()) add("$label.author must not be blank")
            if (!source.date.isIsoDate()) add("$label.date must be an ISO-8601 date")
            if (!source.url.isValidHttpsUrl()) add("$label.url must be an absolute HTTPS URL")
            if (source.url.isNotBlank() && !sourceUrls.add(source.url)) add("duplicate source URL '${source.url}'")
        }

        if (profile.mechanics.isEmpty()) add("mechanics must not be empty")
        if (profile.archetypes.isEmpty()) add("archetypes must not be empty")

        val sourceKinds = profile.sources.associate { it.id to it.kind }
        val mechanicNames = mutableSetOf<String>()
        profile.mechanics.forEachIndexed { index, mechanic ->
            val label = "mechanics[$index]"
            if (mechanic.name.isBlank()) add("$label.name must not be blank")
            if (mechanic.name.isNotBlank() && !mechanicNames.add(mechanic.name.lowercase())) {
                add("duplicate mechanic '${mechanic.name}'")
            }
            if (mechanic.summary.isBlank()) add("$label.summary must not be blank")
            validateReferences(label, mechanic.sourceIds, sourceIds)
            requireKinds(
                label,
                mechanic.sourceIds,
                sourceKinds,
                setOf(SynergySourceKind.OFFICIAL_MECHANICS, SynergySourceKind.OFFICIAL_RELEASE_NOTES),
            )
        }
        val archetypePairs = mutableSetOf<String>()
        profile.archetypes.forEachIndexed { index, archetype ->
            val label = "archetypes[$index]"
            if (archetype.pair !in COLOR_PAIRS) add("$label.pair is not a supported canonical color pair")
            if (!archetypePairs.add(archetype.pair)) add("duplicate archetype pair '${archetype.pair}'")
            if (archetype.name.isBlank()) add("$label.name must not be blank")
            if (archetype.playstyle.isBlank()) add("$label.playstyle must not be blank")
            if (archetype.speed.lowercase() !in SPEEDS) add("$label.speed must be one of ${SPEEDS.joinToString()}")
            val roles = listOf(
                "signposts" to archetype.signposts,
                "payoffs" to archetype.payoffs,
                "enablers" to archetype.enablers,
                "keyCards" to archetype.keyCards,
            )
            roles.forEach { (role, cards) -> validateCardNames("$label.$role", cards) }
            if (roles.sumOf { it.second.size } < 4) add("$label must list at least four role cards")
            validateReferences(label, archetype.sourceIds, sourceIds)
            val kinds = archetype.sourceIds.mapNotNull(sourceKinds::get).toSet()
            if (SynergySourceKind.LIMITED_DATA !in kinds) add("$label must cite Limited data")
            if (kinds.none { it == SynergySourceKind.OFFICIAL_LIMITED_GUIDE || it == SynergySourceKind.OFFICIAL_MECHANICS }) {
                add("$label must cite an official Limited guide or mechanics source")
            }
        }
        profile.combos.forEachIndexed { index, combo ->
            val label = "combos[$index]"
            validateCardNames("$label.cards", combo.cards)
            if (combo.cards.distinct().size < 2) add("$label must contain at least two distinct cards")
            if (combo.note.isBlank()) add("$label.note must not be blank")
            validateReferences(label, combo.sourceIds, sourceIds)
            requireKinds(
                label,
                combo.sourceIds,
                sourceKinds,
                setOf(SynergySourceKind.OFFICIAL_RELEASE_NOTES),
            )
        }
    }

    fun isValid(profile: SetSynergyProfile, expectedSet: String? = null): Boolean =
        errors(profile, expectedSet).isEmpty()

    private fun MutableList<String>.validateReferences(
        label: String,
        references: List<String>,
        knownSourceIds: Set<String>,
    ) {
        if (references.isEmpty()) add("$label.sourceIds must not be empty")
        if (references.size != references.toSet().size) add("$label.sourceIds contains duplicates")
        references.filterNot(knownSourceIds::contains).forEach { add("$label references unknown source '$it'") }
    }

    private fun MutableList<String>.validateCardNames(label: String, cards: List<String>) {
        if (cards.any(String::isBlank)) add("$label contains a blank card name")
        val normalized = cards.map { SynergyIndex.normalize(it) }
        if (normalized.size != normalized.toSet().size) add("$label contains duplicate card names")
    }

    private fun MutableList<String>.requireKinds(
        label: String,
        references: List<String>,
        sourceKinds: Map<String, SynergySourceKind>,
        required: Set<SynergySourceKind>,
    ) {
        val present = references.mapNotNull(sourceKinds::get).toSet()
        required.filterNot(present::contains).forEach { add("$label must cite source kind $it") }
    }

    private fun String.isIsoDate(): Boolean = isNotBlank() && runCatching { LocalDate.parse(this) }.isSuccess

    private fun String.isValidHttpsUrl(): Boolean = runCatching {
        val uri = URI(this)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.isAbsolute
    }.getOrDefault(false)
}

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
        val profile = runCatching { json.decodeFromString<SetSynergyProfile>(body) }
            .onFailure { Log.warn(TAG, "bad synergy profile for $key: $it") }
            .getOrNull()
            ?: return null
        val errors = SynergyProfileValidator.errors(profile, key)
        if (errors.isNotEmpty()) {
            Log.warn(TAG, "invalid synergy profile for $key: ${errors.joinToString("; ")}")
            return null
        }
        return profile
    }

    internal fun indexProfile(profile: SetSynergyProfile) {
        index = SynergyIndex(profile)
        loadedSet = profile.set.uppercase()
    }

    companion object {
        private const val TAG = "Synergy"
    }
}
