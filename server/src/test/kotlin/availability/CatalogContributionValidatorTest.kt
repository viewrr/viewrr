package wtf.jobin.availability

import kotlinx.coroutines.runBlocking
import wtf.jobin.db.ContentUuid
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #124 (P2P-ADR 0008): the anti-poison gate for mesh-contributed catalog rows. These are "real
 * proof" tests — the structural layer is exercised against the FROZEN [ContentUuid] contract (an
 * honest address is recomputed, not hand-copied), and the TMDB layer is driven through a fake
 * [TmdbExistence] seam so no network is touched. If a peer could bind a bogus address to a title,
 * or invent a title, one of these fails.
 */
class CatalogContributionValidatorTest {

    private fun validator(existence: Boolean?) =
        CatalogContributionValidator { _, _ -> existence }

    /** Honest contribution: address IS the UUIDv5 of the identity, and TMDB confirms it exists. */
    @Test
    fun acceptsHonestlyAddressedExistingTitle() = runBlocking {
        val honest = ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE)
        val verdict = validator(existence = true).verify(
            CatalogContribution(tmdbId = 550, kind = ContentUuid.Kind.MOVIE, claimedContentUuid = honest),
        )
        assertEquals(ContributionVerdict.Accepted, verdict)
    }

    /** The core anti-poison check: a claimed address that is NOT the identity's UUIDv5 is refused, */
    /** even though TMDB says the (mis-declared) id exists. */
    @Test
    fun rejectsAddressThatDoesNotDeriveFromIdentity() = runBlocking {
        val wrongAddress = ContentUuid.forTmdb(603, ContentUuid.Kind.MOVIE) // address for a DIFFERENT title
        val verdict = validator(existence = true).verify(
            CatalogContribution(tmdbId = 550, kind = ContentUuid.Kind.MOVIE, claimedContentUuid = wrongAddress),
        )
        assertEquals(ContributionVerdict.Rejected(ContributionVerdict.Reason.ADDRESS_MISMATCH), verdict)
    }

    /** TV episode addressing is part of the frozen name; season/episode must be carried into the check. */
    @Test
    fun acceptsTvEpisodeWithMatchingSeasonEpisodeAddress() = runBlocking {
        val ep = ContentUuid.forTmdb(1399, ContentUuid.Kind.TV, season = 1, episode = 1)
        val verdict = validator(existence = true).verify(
            CatalogContribution(
                tmdbId = 1399, kind = ContentUuid.Kind.TV, claimedContentUuid = ep, season = 1, episode = 1,
            ),
        )
        assertEquals(ContributionVerdict.Accepted, verdict)
    }

    /** Same tmdb id + kind but the wrong season/episode is a different address -> mismatch. */
    @Test
    fun rejectsTvContributionWithWrongEpisode() = runBlocking {
        val s1e1 = ContentUuid.forTmdb(1399, ContentUuid.Kind.TV, season = 1, episode = 1)
        val verdict = validator(existence = true).verify(
            CatalogContribution(
                tmdbId = 1399, kind = ContentUuid.Kind.TV, claimedContentUuid = s1e1, season = 1, episode = 2,
            ),
        )
        assertEquals(ContributionVerdict.Rejected(ContributionVerdict.Reason.ADDRESS_MISMATCH), verdict)
    }

    /** A definitive TMDB "not found" (404 -> false) rejects even a structurally-honest address. */
    @Test
    fun rejectsFabricatedTitleWhenTmdbSaysNotFound() = runBlocking {
        val honest = ContentUuid.forTmdb(999999999, ContentUuid.Kind.MOVIE)
        val verdict = validator(existence = false).verify(
            CatalogContribution(tmdbId = 999999999, kind = ContentUuid.Kind.MOVIE, claimedContentUuid = honest),
        )
        assertEquals(ContributionVerdict.Rejected(ContributionVerdict.Reason.TMDB_NOT_FOUND), verdict)
    }

    /** Unknown existence (TMDB disabled/transient -> null) does NOT invent a rejection; structure stands. */
    @Test
    fun acceptsOnHonestAddressWhenExistenceUnknown() = runBlocking {
        val honest = ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE)
        val verdict = validator(existence = null).verify(
            CatalogContribution(tmdbId = 550, kind = ContentUuid.Kind.MOVIE, claimedContentUuid = honest),
        )
        assertEquals(ContributionVerdict.Accepted, verdict)
    }

    /** A non-positive tmdb id can have no honest address and is refused before any network call. */
    @Test
    fun rejectsNonPositiveTmdbIdWithoutProbing() = runBlocking {
        var probed = false
        val gate = CatalogContributionValidator { _, _ -> probed = true; true }
        val verdict = gate.verify(
            CatalogContribution(tmdbId = 0, kind = ContentUuid.Kind.MOVIE, claimedContentUuid = UUID.randomUUID()),
        )
        assertEquals(ContributionVerdict.Rejected(ContributionVerdict.Reason.INVALID_TMDB_ID), verdict)
        assertEquals(false, probed) // gate short-circuits; no wasted TMDB call
    }
}
