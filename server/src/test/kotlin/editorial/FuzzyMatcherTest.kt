package wtf.jobin.editorial

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatcherTest {
    private val dune = MovieTitle(UUID.randomUUID(), "Dune.2021.2160p.BluRay", "Dune", 2021)
    private val batman = MovieTitle(UUID.randomUUID(), "The Batman", "The Batman", 2022)
    private val poorThings = MovieTitle(UUID.randomUUID(), "Poor Things", "Poor Things", 2023)
    private val cands = listOf(dune, batman, poorThings)

    @Test fun quotedHeadlineExtraction() {
        assertEquals("Poor Things", FuzzyMatcher.extractMovieTitle("'Poor Things' Review: a bold fantasia"))
    }

    @Test fun trailingReviewExtraction() {
        assertEquals("Oppenheimer", FuzzyMatcher.extractMovieTitle("Oppenheimer review"))
    }

    @Test fun leadingReviewColonExtraction() {
        assertEquals("Barbie", FuzzyMatcher.extractMovieTitle("Review: Barbie"))
    }

    @Test fun matchesQuotedTitleToCleanTitle() {
        val m = FuzzyMatcher.match("'Dune' Review", null, cands)
        assertNotNull(m)
        assertEquals(dune.id, m.id)
        assertTrue(m.score >= FuzzyMatcher.THRESHOLD)
    }

    @Test fun matchesWithYearAgreement() {
        val m = FuzzyMatcher.match("Poor Things movie review", 2023, cands)
        assertNotNull(m)
        assertEquals(poorThings.id, m.id)
    }

    @Test fun noMatchForUnknownMovie() {
        assertNull(FuzzyMatcher.match("'The Godfather' Review", 1972, cands))
    }

    @Test fun doesNotConfuseSharedWord() {
        // "The Notebook" shares only the stopword "the" with "The Batman" -> below threshold
        assertNull(FuzzyMatcher.match("'The Notebook' Review", null, cands))
    }
}
