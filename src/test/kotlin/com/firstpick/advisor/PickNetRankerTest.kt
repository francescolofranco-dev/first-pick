package com.firstpick.advisor

import com.firstpick.cards.RankedCard
import com.firstpick.model.PickNet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PickNetRankerTest {


    private fun net(): PickNet {
        val header = """{"v":1,"set":"TST","format":"PremierDraft","hidden":2,"cards":["Angel","Bolt","Carrion"]}"""
        val floats = FloatArray(2 * 3) + FloatArray(2) +
            FloatArray(2 * 2) + FloatArray(2) +
            FloatArray(3 * 2) + floatArrayOf(1f, 2f, 3f)
        val body = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { body.putFloat(it) }
        val file = kotlin.io.path.createTempFile("ranker", ".fpnet")
        java.nio.file.Files.write(file, header.toByteArray() + '\n'.code.toByte() + body.array())
        return PickNet.load(file)
    }

    private fun scored(
        name: String,
        value: Double,
        guardrail: PickGuardrail = PickGuardrail.OPEN,
    ) = ScoredCard(
        card = RankedCard(grpId = name.hashCode(), name = name, rating = null),
        value = value,
        z = 0.0,
        isBomb = false,
        reasons = listOf("On-color"),
        breakdown = ValueBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, finalScore = value),
        rawValue = value,
        guardrail = guardrail,
    )

    private fun constrained() = PickGuardrail(
        status = ModelPromotionStatus.CONSTRAINED,
        laneColors = setOf('W', 'U'),
        offColors = setOf('B'),
        constraints = setOf(GuideConstraint.UNSUPPORTED_SPLASH),
    )

    @Test
    fun `net order wins, values transplant rank-preservingly`() {

        val ranked = PickNetRanker.rerank(net(), listOf(scored("Angel", 80.0), scored("Bolt", 60.0), scored("Carrion", 40.0)), emptyList())
        assertNotNull(ranked)
        assertEquals(listOf("Carrion", "Bolt", "Angel"), ranked.map { it.card.name })

        assertEquals(listOf(80.0, 60.0, 40.0), ranked.map { it.value })


        val carrion = ranked[0].breakdown!!
        assertEquals(80.0, carrion.finalScore)
        assertEquals(40.0, carrion.modelShift)

        assertNotNull(ranked[1].breakdown)
        assertEquals(0.0, ranked[1].breakdown!!.modelShift)

        assertEquals(-40.0, ranked[2].breakdown!!.modelShift)

        assertEquals(PickNetRanker.MODEL_PICK_REASON, ranked[0].reasons.first())
        assertTrue(ranked[1].reasons.none { it == PickNetRanker.MODEL_PICK_REASON })

        assertEquals(listOf(1, 2, 3), ranked.map { it.modelRank })
    }

    @Test
    fun `agreeing top pick is not flagged`() {
        val ranked = PickNetRanker.rerank(net(), listOf(scored("Carrion", 80.0), scored("Bolt", 60.0)), emptyList())
        assertNotNull(ranked)
        assertEquals("Carrion", ranked[0].card.name)
        assertTrue(ranked[0].reasons.none { it == PickNetRanker.MODEL_PICK_REASON })
        assertNotNull(ranked[0].breakdown)
        assertEquals(0.0, ranked[0].breakdown!!.modelShift)
    }

    @Test
    fun `low coverage falls back to heuristics`() {
        val pack = listOf(scored("Angel", 80.0), scored("Mystery1", 70.0), scored("Mystery2", 60.0))
        assertNull(PickNetRanker.rerank(net(), pack, emptyList()))
    }

    @Test
    fun `unknown card in a known pack ranks last`() {

        val pack = listOf(
            scored("Angel", 80.0), scored("Bolt", 70.0), scored("Carrion", 60.0),
            scored("Angel", 55.0), scored("Mystery", 50.0),
        )
        val ranked = PickNetRanker.rerank(net(), pack, emptyList())
        assertNotNull(ranked)
        assertEquals("Mystery", ranked.last().card.name)
    }

    @Test
    fun `model favorite cannot leapfrog viable cards when guide constrains it`() {
        val pack = listOf(
            scored("Angel", 80.0),
            scored("Bolt", 60.0),
            // Its display reason deliberately says On-color: policy must use typed metadata, not prose.
            scored("Carrion", 40.0, constrained()),
        )

        val ranked = PickNetRanker.rerank(net(), pack, emptyList())

        assertNotNull(ranked)
        assertEquals(listOf("Bolt", "Angel", "Carrion"), ranked.map { it.card.name })
        assertEquals(listOf(80.0, 60.0, 40.0), ranked.map { it.value })
        assertEquals(PickNetRanker.MODEL_PICK_REASON, ranked.first().reasons.first())
        assertEquals(1, ranked.last().modelRank, "raw model rank remains visible after the guide veto")
    }

    @Test
    fun `light splash candidate remains promotable by the model`() {
        val splash = PickGuardrail(
            status = ModelPromotionStatus.SPLASH_CANDIDATE,
            laneColors = setOf('W', 'U'),
            offColors = setOf('B'),
        )
        val pack = listOf(
            scored("Angel", 80.0),
            scored("Bolt", 60.0),
            scored("Carrion", 40.0, splash),
        )

        val ranked = PickNetRanker.rerank(net(), pack, emptyList())

        assertNotNull(ranked)
        assertEquals("Carrion", ranked.first().card.name)
    }

    @Test
    fun `all-constrained pack keeps heuristic order instead of manufacturing a model promotion`() {
        val pack = listOf(
            scored("Angel", 80.0, constrained()),
            scored("Bolt", 60.0, constrained()),
            scored("Carrion", 40.0, constrained()),
        )

        val ranked = PickNetRanker.rerank(net(), pack, emptyList())

        assertNotNull(ranked)
        assertEquals(listOf("Angel", "Bolt", "Carrion"), ranked.map { it.card.name })
        assertTrue(ranked.first().reasons.none { it == PickNetRanker.MODEL_PICK_REASON })
    }
}
