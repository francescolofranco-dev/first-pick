package com.firstpick.advisor

import com.firstpick.cards.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ThreeColorDeckTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun completedFraPoolOffersTemurInsteadOfWeakRakdos() = runBlocking {
        withPool { pool, metrics, meta, pairRatings ->
            // The live UI initially has only its detected lane's pair ratings;
            // the CLI loads all pairs. Both paths must find the alternative.
            val synergy = SynergyIndex(json.decodeFromString<SetSynergyProfile>(
                javaClass.getResource("/synergy/FRA.json")!!.readText()))
            for (loadedPairs in listOf(setOf("UR"), pairRatings.keys)) {
                val options = DeckBuilder.build(pool, metrics, meta,
                    archetypeRating = { name, pair -> pairRatings[pair]?.get(name).takeIf { pair in loadedPairs } }, synergy = synergy)
                assertEquals("UR", options.first().colors)
                val temurOptions = options.filter { it.colors == "URG" }
                val conservative = temurOptions.single { it.threeColorPlan == ThreeColorPlan.CONSERVATIVE }
                // Softer creature targets can improve the base enough that a
                // riskier third-color list is no longer a worthwhile alternative.
                assertTrue(temurOptions.size in 1..2)
                temurOptions.singleOrNull { it.threeColorPlan == ThreeColorPlan.BOLD }?.let { bold ->
                    assertTrue(bold.spells.count { 'G' in meta(it.name)?.coloredPips.orEmpty() } >
                        conservative.spells.count { 'G' in meta(it.name)?.coloredPips.orEmpty() })
                    assertTrue(bold.manaSources!!.shortfalls.values.sum() > conservative.manaSources!!.shortfalls.values.sum())
                    assertTrue(bold.deckWinRate > conservative.deckWinRate)
                }
                assertEquals(options.size, options.map { it.spells.groupingBy { card -> card.name }.eachCount() }.distinct().size)
                for (temur in temurOptions) {
                    assertTrue(temur.isThreeColor)
                    assertEquals(23, temur.spells.size)
                    assertEquals(40, temur.spells.size + temur.landCount)
                    val names = temur.spells.map { it.name }
                    assertTrue("Mind Meanderer" in names)
                    assertTrue("Chandra, Torch of Defiance" in names)
                    assertTrue("Kiora of Fire and Ashes" in names)
                    assertTrue("Verdant Kraken" !in names, "GGG is too demanding for this mana base")
                    assertEquals(setOf("Transformative Commons", "Innovative Commons"), temur.nonbasicLands.map { it.name }.toSet())
                    assertTrue(temur.manaSources!!.splashShortfall <= if (temur.threeColorPlan == ThreeColorPlan.BOLD) 2 else 0)
                    assertTrue(temur.manaSources.baseShortfall > 0, "the consistency tradeoff must remain visible")
                    assertTrue(temur.powerReasons.any { "sources short" in it })
                    assertTrue(options.none { it.colors == "BR" })
                    val available = pool.groupingBy { it.name }.eachCount()
                    for ((name, count) in (temur.spells + temur.nonbasicLands).groupingBy { it.name }.eachCount()) {
                        assertTrue(count <= available.getValue(name), "invented copy of $name")
                    }
                }
            }
            val unfixed = DeckBuilder.build(pool.filterNot { meta(it.name)?.isLand == true }, metrics, meta)
            assertTrue(unfixed.none { it.isThreeColor }, "strong cards alone cannot justify unsupported mana")
        }
    }

    @Test
    fun thirdColorMustImproveTheSelectedCards() = runBlocking {
        withPool { pool, metrics, meta, _ ->
            val weakened = pool.map { card ->
                if (card.rating?.color?.contains('G') == true && meta(card.name)?.hybridPips.isNullOrEmpty()) {
                    card.copy(rating = card.rating!!.copy(everDrawnWinRate = 0.45))
                } else card
            }
            assertTrue(DeckBuilder.build(weakened, metrics, meta).none { it.colors == "URG" })
        }
    }

    private suspend fun withPool(check: (
        List<RankedCard>, SetMetrics, (String) -> CardMeta?, Map<String, Map<String, CardRating>>,
    ) -> Unit) {
        val fixture = json.parseToJsonElement(javaClass.getResource("/deck-audit/FRA-temur-pool.json")!!.readText()).jsonObject
        val ratings = json.decodeFromJsonElement<List<CardRating>>(fixture.getValue("ratings"))
        val byId = ratings.associateBy { it.mtgaId }
        val pool = fixture.getValue("pool").jsonArray.map { id ->
            val rating = byId[id.jsonPrimitive.int]
            RankedCard(id.jsonPrimitive.int, rating?.name.orEmpty(), rating)
        }
        val pairs = fixture.getValue("pairRatings").jsonObject.mapValues { (_, value) ->
            json.decodeFromJsonElement<List<CardRating>>(value).associateBy { it.name }
        }
        val cache = createTempDirectory("firstpick-temur")
        try {
            Files.writeString(cache.resolve("scryfall8_FRA.json"), fixture.getValue("metadata").toString())
            val http = ScriptedHttpClient()
            val metadata = ScryfallClient(cacheDir = cache, staleness = Duration.ofDays(36500), http = http).setMeta("FRA")
            check(pool, SetMetrics.from(ratings), { metadata[it.lowercase()] }, pairs)
            assertEquals(0, http.requestCount, "regression fixture must stay offline")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }
}
