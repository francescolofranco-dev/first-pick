package com.firstpick.ui

import com.firstpick.advisor.Confidence
import com.firstpick.advisor.PickConfidence
import com.firstpick.advisor.ScoredCard
import com.firstpick.cards.RankedCard

/** Contextual sorting remains useful before observed card power is available. */
internal fun packRows(
    scored: List<ScoredCard>,
    basicLands: List<RankedCard>,
    originalPackIds: List<Int>,
): List<PackCardUi> {
    val ordered = scored.map { it.card to it } +
        basicLands.sortedBy { originalPackIds.indexOf(it.grpId) }.map { it to null }
    val usedIndices = mutableSetOf<Int>()
    return ordered.mapIndexed { i, (card, score) ->
        val origIdx = originalPackIds.withIndex()
            .firstOrNull { (idx, id) -> id == card.grpId && idx !in usedIndices }
            ?.index ?: 0
        usedIndices.add(origIdx)
        val rating = card.rating
        val hasPowerEvidence = score != null && (
            (rating?.gihWr != null && rating.everDrawnGameCount > 0) ||
                rating?.alsa != null || score.modelRank != null
            )
        val reasons = when {
            score == null -> listOf("Basic land", "Not rated")
            hasPowerEvidence -> score.reasons
            else -> listOf("Unrated — power unknown") + score.reasons.filterNot { it == "Bomb" }
        }
        PackCardUi(
            grpId = card.grpId,
            originalIndex = origIdx,
            rank = i + 1,
            name = card.displayName,
            color = rating?.color.orEmpty(),
            rarity = rating?.rarity.orEmpty(),
            gihWr = card.gihWr,
            alsa = rating?.alsa,
            ata = rating?.ata,
            value = score?.value?.takeIf { hasPowerEvidence },
            isBomb = hasPowerEvidence && score?.isBomb == true,
            isRoom = rating?.types.orEmpty().any { it.contains("Room", ignoreCase = true) },
            isBasicLand = card.isBasicLand,
            reasons = reasons,
            imageUrl = card.imageUrl,
            z = score?.z?.takeIf { hasPowerEvidence } ?: 0.0,
            breakdown = score?.breakdown?.takeIf { hasPowerEvidence },
            modelRank = score?.modelRank?.takeIf { hasPowerEvidence },
        )
    }
}

internal fun hasUnratedCards(cards: List<PackCardUi>): Boolean =
    cards.any { !it.isBasicLand && it.value == null }

internal fun packConfidence(cards: List<PackCardUi>): PickConfidence? {
    if (hasUnratedCards(cards)) return null
    val values = cards.mapNotNull { it.value }
    return if (values.size >= 2) Confidence.of(values) else null
}
