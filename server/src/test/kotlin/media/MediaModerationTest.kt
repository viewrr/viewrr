package wtf.jobin.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #128: self-check on the public-catalog visibility rule. This asserts the
 * canonical Kotlin predicate that every discovery surface mirrors in SQL
 * (publicCatalogOp() for Exposed queries; the WHERE clause in MediaSearchService
 * for the raw BM25 query). If this rule ever changes, those SQL mirrors must too.
 */
class MediaModerationTest {

    @Test
    fun deindexedTitleIsExcluded() {
        // De-indexed by an operator — hidden even with a valid TMDB match.
        assertFalse(MediaModeration.isPubliclyIndexable(deindexed = true, tmdbId = 603))
    }

    @Test
    fun nonTmdbTitleIsExcluded() {
        // Allowlist gate: no TMDB match => private-by-default, even if not de-indexed.
        assertFalse(MediaModeration.isPubliclyIndexable(deindexed = false, tmdbId = null))
    }

    @Test
    fun deindexedAndNonTmdbIsExcluded() {
        assertFalse(MediaModeration.isPubliclyIndexable(deindexed = true, tmdbId = null))
    }

    @Test
    fun matchedAndNotDeindexedIsSurfaced() {
        // The only surfaced case: TMDB-matched and not de-indexed.
        assertTrue(MediaModeration.isPubliclyIndexable(deindexed = false, tmdbId = 603))
    }
}
