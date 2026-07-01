package wtf.jobin.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #128: self-check on the SPLIT visibility rules.
 *
 *  - Owner surfaces (`/media` browse, `/home` rows, `/media/search`) mirror
 *    [MediaModeration.isVisibleToOwner] (de-index only) in SQL — notDeindexedOp()
 *    and the `deindexed = false` WHERE clause in MediaSearchService.
 *  - The public Stremio catalog mirrors [MediaModeration.isPubliclyIndexable]
 *    (de-index AND TMDB allowlist) via publicCatalogOp().
 *
 * If either rule changes, its SQL mirrors must change with it.
 */
class MediaModerationTest {

    // --- Owner surfaces: de-index only ---

    @Test
    fun ownerSeesNonTmdbTitle() {
        // The key split property: a non-deindexed Title with no TMDB match is still
        // visible to its owner — a missing match must not hide owner media.
        assertTrue(MediaModeration.isVisibleToOwner(deindexed = false))
    }

    @Test
    fun ownerDoesNotSeeDeindexedTitle() {
        assertFalse(MediaModeration.isVisibleToOwner(deindexed = true))
    }

    // --- Public Stremio catalog: de-index AND TMDB allowlist ---

    @Test
    fun publicExcludesDeindexedTitle() {
        // De-indexed => hidden even with a valid TMDB match.
        assertFalse(MediaModeration.isPubliclyIndexable(deindexed = true, tmdbId = 603))
    }

    @Test
    fun publicExcludesNonTmdbTitle() {
        // Allowlist gate: no TMDB match => private-by-default, even if not de-indexed.
        assertFalse(MediaModeration.isPubliclyIndexable(deindexed = false, tmdbId = null))
    }

    @Test
    fun publicExcludesDeindexedAndNonTmdb() {
        assertFalse(MediaModeration.isPubliclyIndexable(deindexed = true, tmdbId = null))
    }

    @Test
    fun publicSurfacesMatchedNonDeindexedTitle() {
        assertTrue(MediaModeration.isPubliclyIndexable(deindexed = false, tmdbId = 603))
    }

    // --- The split, stated as one assertion ---

    @Test
    fun nonTmdbNonDeindexedIsOwnerVisibleButNotPublic() {
        assertTrue(MediaModeration.isVisibleToOwner(deindexed = false))
        assertFalse(MediaModeration.isPubliclyIndexable(deindexed = false, tmdbId = null))
    }
}
