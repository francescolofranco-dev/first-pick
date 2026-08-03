package com.firstpick.overlay

enum class DeckGuidanceAction { ADD, REMOVE, OK }

enum class DeckGuidanceStatus { READING, NEEDS_CHANGES, MATCHES, UNABLE }

data class DeckCardCount(
    val name: String,
    val count: Int,
    val isBasicLand: Boolean = false,
) {
    init {
        require(name.isNotBlank()) { "card name must not be blank" }
        require(count >= 0) { "card count must not be negative" }
    }
}

data class DeckSnapshot(
    val cards: List<DeckCardCount>,
    val totalCards: Int,
) {
    init {
        require(totalCards >= 0) { "total card count must not be negative" }
    }
}

sealed interface DeckReadState {
    data object Reading : DeckReadState
    data class Ready(val deck: DeckSnapshot) : DeckReadState
    data object Unable : DeckReadState
}

data class CardGuidance(
    val name: String,
    val targetCount: Int,
    val currentCount: Int,
) {
    val difference: Int get() = targetCount - currentCount

    val action: DeckGuidanceAction
        get() = when {
            difference > 0 -> DeckGuidanceAction.ADD
            difference < 0 -> DeckGuidanceAction.REMOVE
            else -> DeckGuidanceAction.OK
        }
}

data class DeckGuidanceResult(
    val status: DeckGuidanceStatus,
    val cards: List<CardGuidance> = emptyList(),
    /** Basic lands to add (> 0) or remove (< 0) after the named-card changes. */
    val basicLandDelta: Int = 0,
) {
    fun forCard(name: String): CardGuidance? = cards.firstOrNull { it.name == name }

    val remainingAdds: Int
        get() = cards.sumOf { it.difference.coerceAtLeast(0) }

    /** Excess named-card copies, not the number of visible marks or cards over forty. */
    val remainingRemovals: Int
        get() = cards.sumOf { (-it.difference).coerceAtLeast(0) }

    /** Net cards to add (> 0) or remove (< 0) after applying every instruction. */
    val netCardDelta: Int
        get() = remainingAdds - remainingRemovals + basicLandDelta
}

object DeckGuidance {
    const val LIMITED_DECK_SIZE = 40

    fun evaluate(
        target: List<DeckCardCount>,
        current: DeckReadState,
    ): DeckGuidanceResult = when (current) {
        DeckReadState.Reading -> DeckGuidanceResult(DeckGuidanceStatus.READING)
        DeckReadState.Unable -> DeckGuidanceResult(DeckGuidanceStatus.UNABLE)
        is DeckReadState.Ready -> evaluateReady(target, current.deck)
    }

    private fun evaluateReady(
        target: List<DeckCardCount>,
        current: DeckSnapshot,
    ): DeckGuidanceResult {
        val targetCounts = target.nonbasicCounts()
        val currentCounts = current.cards.nonbasicCounts()
        val cards = (targetCounts.keys + currentCounts.keys)
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { name ->
                CardGuidance(
                    name = name,
                    targetCount = targetCounts[name] ?: 0,
                    currentCount = currentCounts[name] ?: 0,
                )
            }
        val exactNonbasicCounts = cards.all { it.action == DeckGuidanceAction.OK }
        val namedCardDelta = cards.sumOf(CardGuidance::difference)
        val basicLandDelta = LIMITED_DECK_SIZE - (current.totalCards + namedCardDelta)
        val status = if (exactNonbasicCounts && current.totalCards == LIMITED_DECK_SIZE) {
            DeckGuidanceStatus.MATCHES
        } else {
            DeckGuidanceStatus.NEEDS_CHANGES
        }
        return DeckGuidanceResult(status, cards, basicLandDelta)
    }

    private fun List<DeckCardCount>.nonbasicCounts(): Map<String, Int> =
        asSequence()
            .filterNot(DeckCardCount::isBasicLand)
            .groupingBy(DeckCardCount::name)
            .fold(0) { total, card -> total + card.count }
}
