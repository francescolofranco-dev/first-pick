package com.firstpick.tools

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.DeckBuilder
import com.firstpick.advisor.LaneDetector
import com.firstpick.advisor.PoolNeeds
import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.cards.SynergyRepository
import com.firstpick.draft.DraftTracker
import com.firstpick.model.DraftPhase
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) = runBlocking {
    val logPath: Path? = args.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(Path::of)
    val format = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "PremierDraft"
    val stopAt: Pair<Int, Int>? = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.split(":")
        ?.let { it[0].toInt() to it[1].toInt() }

    val tracker = DraftTracker()
    val lines: Sequence<String> = if (logPath != null) {
        Files.newBufferedReader(logPath, StandardCharsets.UTF_8).useLines { it.toList() }.asSequence()
    } else {
        val res = object {}.javaClass.getResourceAsStream("/quickdraft_first_snapshot.log")!!
        res.bufferedReader().readLines().asSequence()
    }
    for (line in lines) {
        tracker.onLine(line)
        if (stopAt != null) {
            val s = tracker.state.value
            if (s.pack == stopAt.first && s.pick == stopAt.second && s.packCards.isNotEmpty()) break
        }
    }

    val state = tracker.state.value
    if (state.phase == DraftPhase.IDLE || (state.packCards.isEmpty() && state.pool.isEmpty())) {
        println("No draft found in the log.")
        return@runBlocking
    }
    val set = state.setCode ?: error("No set code detected")
    println("Pack P${state.pack}P${state.pick}  set=$set  format=$format  phase=${state.phase}  (${state.packCards.size} pack, ${state.pool.size} pool)\n")

    val repo = CardRepository()
    repo.load(set, format)
    val archRepo = ArchetypeRepository()
    archRepo.loadStrengths(set, format)

    val metaRepo = CardMetaRepository()
    metaRepo.load(set, repo.cardNames)
    val synergyRepo = SynergyRepository()
    synergyRepo.load(set)
    val synergy = synergyRepo.index
    println("Synergy profile: " + (synergy?.let { "${it.profile.setName} — ${it.profile.archetypes.size} archetypes" } ?: "none"))

    val pack = repo.resolvePack(state.packCards)
    val pool = state.pool.map(repo::resolve)
    val lane = LaneDetector.detect(pool, repo.setMetrics, archRepo.strengthMap())
    lane.pair?.let { archRepo.ensurePair(set, format, it) }
    println("Lane: ${lane.pair ?: "undecided"}   Top archetypes: " +
        archRepo.rankedPairs().take(3).joinToString { "${it.pair} ${"%.1f".format(it.winRate * 100)}%" })
    val poolNeeds = PoolNeeds.analyze(pool.mapNotNull { metaRepo.meta(it.name) }, pool.size)
    println("Deck needs: " + (poolNeeds.activeNeeds(45).ifEmpty { listOf("solid backbone") }.joinToString(", ")))
    if (pack.isNotEmpty()) {
        val scored = AdvisorEngine().score(pack, pool, state.pack, state.pick, repo.setMetrics, lane, archRepo::archetypeRating, metaRepo::meta, synergy)
        val plain = AdvisorEngine().score(pack, pool, state.pack, state.pick, repo.setMetrics, lane, archRepo::archetypeRating, metaRepo::meta)
        val plainValue = plain.associate { it.card.grpId to it.value }

        println("  VALUE   Δsyn  GIH%   ALSA  Card / reasons")
        println("  -----  -----  -----  ----  --------------")
        for (s in scored) {
            val r = s.card.rating
            val value = "%.0f".format(s.value)
            val delta = s.value - (plainValue[s.card.grpId] ?: s.value)
            val deltaStr = if (delta != 0.0) "%+.1f".format(delta) else "    "
            val gih = r?.gihWr?.let { "%.1f".format(it * 100) } ?: "  -"
            val alsa = r?.alsa?.let { "%.1f".format(it) } ?: " - "
            val star = if (s.isBomb) "★" else " "
            val reasons = if (s.reasons.isNotEmpty()) "  (${s.reasons.joinToString(", ")})" else ""
            println("  ${value.padStart(5)}  ${deltaStr.padStart(5)}  ${gih.padStart(5)}  ${alsa.padStart(4)}  $star ${s.card.displayName}$reasons")
        }
    }

    if (pool.size >= 20) {
        println("\n=== Deck builder (from ${pool.size} pool cards) ===")
        for (p in com.firstpick.advisor.COLOR_PAIRS) runCatching { archRepo.ensurePair(set, format, p) }
        val options = DeckBuilder.build(pool, repo.setMetrics, metaRepo::meta, archRepo::archetypeRating, archRepo.strengthMap(), synergy = synergy)
        for (o in options) {
            val splash = o.splash?.let { " splash $it" } ?: ""
            val theme = o.theme?.let { " · $it" } ?: ""
            println(
                "\n${o.colors} (${o.basePair}$splash)$theme  tier ${o.tier}  power ${"%.0f".format(o.powerScore)}  ${o.type}  " +
                    "${o.spells.size}sp/${o.landCount}ln  ${o.creatures}cr/${o.removal}rem  — ${o.outlook}",
            )
            if (System.getenv("FIRSTPICK_DECK_DETAIL") == "1") {
                for (s in o.spells) {
                    val m = metaRepo.meta(s.name)
                    println("    ${m?.cmc ?: "?"}  ${(s.rating?.color ?: "").padEnd(3)}  ${"%.1f".format((s.gihWr ?: 0.0) * 100)}  ${s.displayName}")
                }
                if (o.nonbasicLands.isNotEmpty()) println("    lands: ${o.nonbasicLands.joinToString { it.displayName }}")
            }
        }
    }
}
