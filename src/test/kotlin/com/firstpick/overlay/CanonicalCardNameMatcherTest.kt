package com.firstpick.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanonicalCardNameMatcherTest {
    @Test
    fun matchesCasePunctuationDiacriticsAndOcrSpacing() {
        val matcher = CanonicalCardNameMatcher(listOf("Stock Up", "Éowyn, Fearless Knight", "Collector's Cage"))

        assertEquals("Stock Up", matcher.matchedName("STOCK   UP"))
        assertEquals("Stock Up", matcher.matchedName("S t o c k - U p"))
        assertEquals("Éowyn, Fearless Knight", matcher.matchedName("Eowyn Fearless Knight"))
        assertEquals("Collector's Cage", matcher.matchedName("Collectors Cage"))
    }

    @Test
    fun rejectsUnknownAndPartialNamesInsteadOfFuzzyGuessing() {
        val matcher = CanonicalCardNameMatcher(listOf("Stock Up", "Stockman, Mad Fly-entist"))

        assertIs<CanonicalCardNameMatch.NoMatch>(matcher.match("Stock"))
        assertIs<CanonicalCardNameMatch.NoMatch>(matcher.match("Stock..."))
        assertIs<CanonicalCardNameMatch.NoMatch>(matcher.match("Completely Different"))
        assertIs<CanonicalCardNameMatch.NoMatch>(matcher.match("---"))
    }

    @Test
    fun matchesExplicitUnambiguousArenaTruncations() {
        val matcher = CanonicalCardNameMatcher(
            listOf(
                "Black Widow, Double Agent",
                "Crossbones, Malicious Mercenary",
                "Elektra, Daughter of Death",
            ),
        )

        assertEquals("Black Widow, Double Agent", matcher.matchedName("Black Widow, Doubl..."))
        assertEquals("Crossbones, Malicious Mercenary", matcher.matchedName("Crossbones, Malicious …"))
        assertEquals("Elektra, Daughter of Death", matcher.matchedName("Elektra, Daughter of..."))
    }

    @Test
    fun rejectsAmbiguousArenaTruncationsInsteadOfGuessing() {
        val matcher = CanonicalCardNameMatcher(
            listOf(
                "Doc Samson, Super Psychiatrist",
                "Doc Samson, Super Powered",
            ),
        )

        val ambiguous = assertIs<CanonicalCardNameMatch.Ambiguous>(matcher.match("Doc Samson, Super..."))
        assertEquals(
            listOf("Doc Samson, Super Powered", "Doc Samson, Super Psychiatrist"),
            ambiguous.candidates,
        )
    }

    @Test
    fun truncatedPrefixThatIsAlsoAFullNormalizedNameStaysAmbiguous() {
        val matcher = CanonicalCardNameMatcher(
            listOf(
                "Crossbones, Malicious",
                "Crossbones, Malicious Mercenary",
            ),
        )

        assertIs<CanonicalCardNameMatch.Ambiguous>(matcher.match("Crossbones, Malicious..."))
    }

    @Test
    fun rejectsNormalizationCollisionsAsAmbiguous() {
        val matcher = CanonicalCardNameMatcher(listOf("A-B", "A B"))

        val ambiguous = assertIs<CanonicalCardNameMatch.Ambiguous>(matcher.match("ab"))
        assertEquals(listOf("A B", "A-B"), ambiguous.candidates)
    }

    @Test
    fun exactCanonicalTextCanDisambiguateANormalizationCollision() {
        val matcher = CanonicalCardNameMatcher(listOf("A-B", "A B"))

        assertEquals("A-B", matcher.matchedName("a-b"))
        assertEquals("A B", matcher.matchedName("A   B"))
    }

    private fun CanonicalCardNameMatcher.matchedName(observed: String): String =
        assertIs<CanonicalCardNameMatch.Matched>(match(observed)).name
}
