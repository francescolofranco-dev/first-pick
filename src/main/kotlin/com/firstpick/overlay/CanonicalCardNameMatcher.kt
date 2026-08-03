package com.firstpick.overlay

import java.text.Normalizer
import java.util.Locale

sealed interface CanonicalCardNameMatch {
    data class Matched(val name: String) : CanonicalCardNameMatch
    data object NoMatch : CanonicalCardNameMatch
    data class Ambiguous(val candidates: List<String>) : CanonicalCardNameMatch
}

class CanonicalCardNameMatcher(knownNames: Collection<String>) {
    private val names = knownNames.map(String::trim).onEach {
        require(it.isNotEmpty()) { "known card names must not be blank" }
    }.distinct()
    private val exactNames = names.groupBy(::exactKey)
    private val normalizedNames = names.groupBy(::normalizedKey)

    fun match(observedName: String): CanonicalCardNameMatch {
        val exact = exactNames[exactKey(observedName)].orEmpty()
        if (exact.isNotEmpty()) return exact.toMatch()

        // Arena shortens long deck-list titles with an ellipsis. Treat only an
        // explicit, sufficiently long truncation as a prefix; ordinary partial
        // OCR remains a hard no-match, and collisions remain ambiguous.
        val truncatedPrefix = truncatedPrefixKey(observedName)
        if (truncatedPrefix != null) {
            return normalizedNames.asSequence()
                .filter { (knownKey, _) -> knownKey.startsWith(truncatedPrefix) }
                .flatMap { (_, canonicalNames) -> canonicalNames.asSequence() }
                .toList()
                .toMatch()
        }

        val key = normalizedKey(observedName)
        if (key.isEmpty()) return CanonicalCardNameMatch.NoMatch
        return normalizedNames[key].orEmpty().toMatch()
    }

    private fun List<String>.toMatch(): CanonicalCardNameMatch = when (size) {
        0 -> CanonicalCardNameMatch.NoMatch
        1 -> CanonicalCardNameMatch.Matched(single())
        else -> CanonicalCardNameMatch.Ambiguous(sortedWith(String.CASE_INSENSITIVE_ORDER))
    }

    private fun exactKey(value: String): String =
        value.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    private fun normalizedKey(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        return buildString(decomposed.length) {
            for (character in decomposed) if (character.isLetterOrDigit()) append(character)
        }
    }

    private fun truncatedPrefixKey(value: String): String? {
        val trimmed = value.trim()
        val withoutEllipsis = when {
            trimmed.endsWith("...") -> trimmed.dropLast(3).trimEnd()
            trimmed.endsWith('…') -> trimmed.dropLast(1).trimEnd()
            else -> return null
        }
        return normalizedKey(withoutEllipsis).takeIf { it.length >= MIN_TRUNCATED_PREFIX_LENGTH }
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")
        const val MIN_TRUNCATED_PREFIX_LENGTH = 8
    }
}
