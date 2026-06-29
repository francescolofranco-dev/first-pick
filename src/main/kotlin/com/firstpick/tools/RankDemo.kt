package com.firstpick.tools

import com.firstpick.advisor.AdvisorEngine
import com.firstpick.advisor.DeckBuilder
import com.firstpick.advisor.LaneDetector
import com.firstpick.advisor.PoolNeeds
import com.firstpick.cards.ArchetypeRepository
import com.firstpick.cards.CardMetaRepository
import com.firstpick.cards.CardRepository
import com.firstpick.draft.DraftTracker
import com.firstpick.model.DraftPhase
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Ranks one real pack end-to-end: reconstruct a pack from a log, fetch 17Lands
 * data for its set, and print the cards best-first by GIH win rate.
 *
 *   ./gradlew rankDemo                                  # uses the bundled P1P1 fixture
 *   ./gradlew rankDemo -PlogPath="/abs/Player.log" -Pformat=PremierDraft
 */
fun main(args: Array<String>) = runBlocking {
    val logPath: Path? = args.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(Path::of)
    val format = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "PremierDraft"
    // Optional "pack:pick" stop target (e.g. "2:3") to inspect a mid-draft pack.
    val stopAt: Pair<Int, Int>? = args.getOrNull(2)?.takeIf { it.isNotBlank() }?.split(":")
        ?.let { it[0].toInt() to it[1].toInt() }

    // Reconstruct the current pack + set from the log (or the bundled fixture).
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
    if (state.phase == DraftPhase.IDLE || state.packCards.isEmpty()) {
        println("No active pack found in the log.")
        return@runBlocking
    }
    val set = state.setCode ?: error("No set code detected")
    println("Pack P${state.pack}P${state.pick}  set=$set  format=$format  (${state.packCards.size} cards)\n")

    val repo = CardRepository()
    repo.load(set, format)
    val archRepo = ArchetypeRepository()
    archRepo.loadStrengths(set, format)

    val metaRepo = CardMetaRepository()
    metaRepo.load(set, repo.cardNames)

    val pack = repo.resolvePack(state.packCards)
    val pool = state.pool.map(repo::resolve)
    val lane = LaneDetector.detect(pool, repo.setMetrics, archRepo.strengthMap())
    lane.pair?.let { archRepo.ensurePair(set, format, it) }
    println("Lane: ${lane.pair ?: "undecided"}   Top archetypes: " +
        archRepo.rankedPairs().take(3).joinToString { "${it.pair} ${"%.1f".format(it.winRate * 100)}%" })
    val poolNeeds = PoolNeeds.analyze(pool.mapNotNull { metaRepo.meta(it.name) }, pool.size)
    println("Deck needs: " + (poolNeeds.activeNeeds(45).ifEmpty { listOf("solid backbone") }.joinToString(", ")))
    val scored = AdvisorEngine().score(pack, pool, state.pack, state.pick, repo.setMetrics, lane, archRepo::archetypeRating, metaRepo::meta)

    println("  VALUE  GIH%   ALSA  Card / reasons")
    println("  -----  -----  ----  --------------")
    for (s in scored) {
        val r = s.card.rating
        val value = "%.0f".format(s.value)
        val gih = r?.gihWr?.let { "%.1f".format(it * 100) } ?: "  -"
        val alsa = r?.alsa?.let { "%.1f".format(it) } ?: " - "
        val star = if (s.isBomb) "★" else " "
        val reasons = if (s.reasons.isNotEmpty()) "  (${s.reasons.joinToString(", ")})" else ""
        println("  ${value.padStart(5)}  ${gih.padStart(5)}  ${alsa.padStart(4)}  $star ${s.card.displayName}$reasons")
    }

    if (pool.size >= 20) {
        println("\n=== Deck builder (from ${pool.size} pool cards) ===")
        val options = DeckBuilder.build(pool, repo.setMetrics, metaRepo::meta, archRepo::archetypeRating, archRepo.strengthMap())
        for (o in options) {
            val splash = o.splash?.let { " splash $it" } ?: ""
            println(
                "${o.colors} (${o.basePair}$splash)  tier ${o.tier}  power ${"%.0f".format(o.powerScore)}  ${o.type}  " +
                    "${o.spells.size}sp/${o.landCount}ln  ${o.creatures}cr/${o.removal}rem  — ${o.outlook}",
            )
        }
    }
}
