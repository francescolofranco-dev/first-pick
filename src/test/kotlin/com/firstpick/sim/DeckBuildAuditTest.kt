package com.firstpick.sim

import com.firstpick.advisor.*
import com.firstpick.cards.*
import com.firstpick.draft.DraftTracker
import com.firstpick.model.DraftPhase
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration as KotlinDuration

/** Seeded full demo drafts plus unstructured random pools, using frozen multi-set observations. */
class DeckBuildAuditTest {
    @Serializable
    private data class Row(val rating: CardRating, val meta: JsonObject)
    private val json = Json { ignoreUnknownKeys = true }
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull == true
    private fun JsonObject.strings(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun Row.metadata() = CardMeta(
        rating.name, meta["cmc"]!!.jsonPrimitive.int, meta.flag("creature"), meta.flag("land"),
        isRemoval = meta.flag("removal"), isFixing = meta.flag("fixing"),
        isFinisher = meta.flag("finisher"), isEvasion = meta.flag("evasion"), isCardDraw = meta.flag("draw"),
        hybridColorGroups = meta.strings("hybridGroups").map { it.toSet() },
        producedColors = meta.string("produced").toSet(), heavyPipColors = meta.string("heavyPips").toSet(),
        coloredPips = meta.string("coloredPips").groupingBy { it }.eachCount(),
        hybridPips = meta.strings("hybridPips").map { it.toSet() },
        fixingRequiresAdditionalSpell = meta.flag("fixingRequiresAdditionalSpell"),
        manaCost = meta["manaCost"]?.jsonPrimitive?.contentOrNull,
        isPlaneswalker = meta.flag("planeswalker"), isMassRemoval = meta.flag("massRemoval"),
        isRepeatableAdvantage = meta.flag("repeatableAdvantage"),
    )

    @Test
    fun auditCompleteRandomizedDraftsAcrossSets() = runBlocking {
        val report = StringBuilder("# Seeded deck-building audit\n\n")
        var findings = 0
        var builds = 0
        var completeBuilds = 0
        var splashBuilds = 0
        var threeOptionPools = 0
        val firstSeed = System.getProperty("firstpick.audit.seedStart", "0").toInt()
        val seeds = System.getProperty("firstpick.audit.seeds", "20").toInt()
        require(seeds in 1..1000 && firstSeed >= 0)
        for (set in listOf("FRA", "BLB", "DSK")) {
            val rows = json.decodeFromString<List<Row>>(
                javaClass.getResourceAsStream("/deck-audit/$set.json")!!.bufferedReader().use { it.readText() },
            )
            val cache = createTempDirectory("fp-deck-audit")
            val ratings = rows.map { it.rating }
            Files.writeString(cache.resolve("ratings3_${set}_PremierDraft.json"),
                json.encodeToString(buildJsonObject { put("data", json.encodeToJsonElement(ratings)) }))
            val http = ScriptedHttpClient()
            val client = SeventeenLandsClient(cacheDir = cache, staleness = Duration.ofDays(36500), http = http)
            val repo = CardRepository(client)
            repo.load(set, "PremierDraft")
            val metrics = repo.setMetrics
            val metadata = rows.associate { it.rating.name to it.metadata() }
            val meta: (String) -> CardMeta? = metadata::get
            val profile = json.decodeFromString<SetSynergyProfile>(
                javaClass.getResourceAsStream("/synergy/$set.json")!!.bufferedReader().use { it.readText() },
            )
            val synergy = SynergyIndex(profile)
            val all = ratings.map { repo.resolve(it.mtgaId!!) }
            report.append("$set: ${rows.size} cards; ${metadata.values.count { it.manaCost != null }} with exact printed costs.\n")
            for (seed in firstSeed until firstSeed + seeds) {
                val tracker = DraftTracker()
                var events = 0
                DraftSimulator(client, KotlinDuration.ZERO, Random(seed), repo).simulate(set).collect {
                    assertTrue(tracker.onLine(it), "$set/$seed: unparseable event")
                    events++
                }
                assertEquals(46, events)
                assertEquals(DraftPhase.COMPLETE, tracker.state.value.phase)
                assertEquals(45, tracker.state.value.pool.size)
                val demo = tracker.state.value.pool.map(repo::resolve)
                val random = List(45) { all[Random(seed * 1000 + it).nextInt(all.size)] }
                for ((kind, pool) in listOf("demo" to demo, "random" to random)) {
                    val options = DeckBuilder.build(pool, metrics, meta, synergy = synergy)
                    assertTrue(options.isNotEmpty(), "$set/$seed/$kind: no deck suggestions")
                    assertTrue(options.size <= 3, "$set/$seed/$kind: exceeded the requested option count")
                    assertEquals(options, DeckBuilder.build(pool, metrics, meta, synergy = synergy),
                        "$set/$seed/$kind: non-deterministic result")
                    if (options.size == 3) threeOptionPools++
                    val poolCounts = pool.groupingBy { it.name }.eachCount()
                    for (option in options) {
                        builds++
                        if (option.spells.size == 23) completeBuilds++
                        if (option.splash != null) splashBuilds++
                        val label = "$set seed=$seed $kind ${option.basePair}/${option.splash ?: "-"}"
                        val selected = option.spells + option.nonbasicLands
                        for ((name, count) in selected.groupingBy { it.name }.eachCount()) {
                            assertTrue(count <= poolCounts.getValue(name), "$label: invented copy of $name")
                        }
                        assertEquals(40, option.spells.size + option.landCount, label)
                        assertTrue(option.spells.none { meta(it.name)!!.isLand }, label)
                        assertTrue(option.nonbasicLands.size <= option.landCount, label)
                        for (card in option.spells) {
                            val m = meta(card.name)!!
                            val colors = LaneDetector.colorsOf(card)
                            assertTrue(LaneDetector.uncastableColorOptions(colors, option.colors.toSet(),
                                m.hybridColorGroups, m.coloredPips.keys.takeIf { m.coloredPips.isNotEmpty() })
                                .any { it.isEmpty() }, "$label: off-color ${card.name}")
                        }
                        if (option.splash != null) {
                            assertTrue(option.manaSources!!.splashFeasible, "$label: unsupported splash")
                            assertTrue(option.spells.any { card ->
                                option.splash in LaneDetector.colorsOf(card) &&
                                    DeckProjector.isSplashHaymaker(card, metrics, meta(card.name))
                            }, "$label: splash without haymaker")
                        }
                        val used = option.spells.groupingBy { it.name }.eachCount()
                        val omitted = pool.filter { !meta(it.name)!!.isLand && (used[it.name] ?: 0) < poolCounts.getValue(it.name) }
                        // Conservative local dominance: same color, cost, roles, and archetype tags.
                        // This checks substitutions without using the builder's deck score as the oracle.
                        val dominated = omitted.distinctBy { it.name }.mapNotNull { replacement ->
                            val rm = meta(replacement.name)!!
                            val worse = option.spells.firstOrNull { current ->
                                val cm = meta(current.name)!!
                                rm.copy(name = cm.name) == cm &&
                                    LaneDetector.colorsOf(replacement) == LaneDetector.colorsOf(current) &&
                                    synergy.tags(replacement.name).filter { it.pair == option.basePair }.map { it.role }.toSet() ==
                                        synergy.tags(current.name).filter { it.pair == option.basePair }.map { it.role }.toSet() &&
                                    replacement.rating!!.hasReliableWinRate && current.rating!!.hasReliableWinRate &&
                                    DeckAnalysis.cardQuality(replacement.rating, null, metrics) >
                                        DeckAnalysis.cardQuality(current.rating, null, metrics) + 0.03
                            }
                            worse?.let { "${replacement.name} dominates ${it.name}" }
                        }
                        if (dominated.isNotEmpty()) {
                            findings += dominated.size
                            report.append("- $label: ${dominated.joinToString()}\n")
                        }
                        val baseCount = pool.count { card ->
                            !meta(card.name)!!.isLand && LaneDetector.colorsOf(card).all { it in option.basePair }
                        }
                        if (option.spells.size < 23 && baseCount >= 23) {
                            findings++
                            report.append("- $label: ${option.spells.size} spells despite $baseCount base-color cards\n")
                        }
                    }
                }
            }
            assertEquals(0, http.requestCount, "audit must be offline")
            cache.toFile().deleteRecursively()
        }
        report.append("\n$builds builds; $completeBuilds complete builds; $splashBuilds splash builds; " +
            "$threeOptionPools pools with three options; $findings selection findings.\n")
        report.append("Seeds $firstSeed..${firstSeed + seeds - 1}; ${seeds * 6} pools across three sets and two pool types.\n")
        val output = Path.of("build/reports/deck-audit.md")
        Files.createDirectories(output.parent)
        Files.writeString(output, report)
        Files.writeString(output.resolveSibling("deck-audit-$firstSeed-$seeds.md"), report)
        assertEquals(0, findings, "Selection findings: see $output")
    }
}
