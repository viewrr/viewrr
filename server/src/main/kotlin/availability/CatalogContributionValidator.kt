package wtf.jobin.availability

import wtf.jobin.db.ContentUuid
import java.util.UUID

/**
 * A proposed catalog contribution (P2P-ADR 0008, point 3): a peer/client claims a TMDB-identified
 * title exists at [claimedContentUuid] and asks the central catalog to index that metadata. Because
 * the catalog is *mesh-contributed* and public, an unvalidated write is a poisoning vector — a peer
 * could bind a bogus address to a real title, a real address to the wrong title, or invent a title
 * that does not exist. This is the payload the anti-poison gate scrutinises before any upsert.
 */
data class CatalogContribution(
    val tmdbId: Int,
    val kind: ContentUuid.Kind,
    val claimedContentUuid: UUID,
    val season: Int? = null,   // TV only; ignored for movies (see ContentUuid.forTmdb)
    val episode: Int? = null,  // TV only
)

/** Outcome of the anti-poison gate. Every rejection carries a machine-readable [Reason]. */
sealed class ContributionVerdict {
    /** The contribution is honestly addressed and (as far as TMDB could say) real. Safe to upsert. */
    data object Accepted : ContributionVerdict()

    /** The contribution was refused; [reason] says why (never leaked to the peer verbatim). */
    data class Rejected(val reason: Reason) : ContributionVerdict()

    enum class Reason {
        /** tmdbId <= 0 — not a real TMDB identity, so no honest address can exist. */
        INVALID_TMDB_ID,

        /**
         * [CatalogContribution.claimedContentUuid] is NOT the UUIDv5 of the declared TMDB identity.
         * The single most important anti-poison check: the address MUST be reproducible from the
         * identity by the frozen [ContentUuid] contract, or a peer is lying about one of the two.
         */
        ADDRESS_MISMATCH,

        /** TMDB definitively has no such id (a 404). The title is fabricated. */
        TMDB_NOT_FOUND,
    }
}

/**
 * Best-effort TMDB existence probe, injected as a seam so the gate is unit-testable with a fake (no
 * network). Tri-state on purpose:
 *   - `true`  -> TMDB confirms the id exists,
 *   - `false` -> TMDB confirms it does NOT (a definitive 404 — poison),
 *   - `null`  -> existence is UNKNOWN (TMDB disabled, rate-limited, or a transient error). The gate
 *                does NOT invent a rejection from ignorance; the structural check remains the floor.
 * Production binds this to [wtf.jobin.scanner.TmdbClient.titleExists].
 */
fun interface TmdbExistence {
    suspend fun exists(tmdbId: Int, kind: ContentUuid.Kind): Boolean?
}

/**
 * #124 (P2P-ADR 0008): the TMDB-validated, anti-poison gate every new catalog row must pass — the
 * write-side counterpart to #159's public availability read.
 *
 * Two layers, cheapest first:
 *  1. STRUCTURAL (deterministic, offline, always enforced): recompute the content address from the
 *     declared TMDB identity via the frozen [ContentUuid.forTmdb] contract and require it to equal
 *     the [CatalogContribution.claimedContentUuid]. This alone stops a peer binding an arbitrary
 *     address to a title or claiming a real address for the wrong title — no coordination or network
 *     needed, because the address is a pure function of the identity that every peer agrees on.
 *  2. EXISTENCE (network, best-effort): reject only on a DEFINITIVE TMDB "not found". An unknown
 *     answer (disabled/transient) never fabricates a rejection — structural honesty is the floor and
 *     a no-key deployment still gets the full structural guarantee.
 *
 * Persists nothing and joins nothing: it is a pure decision function. Wiring it into an actual
 * contribution endpoint (auth + upsert into [wtf.jobin.db.MediaItems]) is a later slice — this is
 * the choke point that endpoint will call, kept separately testable exactly like #159 left
 * [AvailabilityService.overHypercore] a ready-but-unstarted capability.
 */
class CatalogContributionValidator(private val tmdb: TmdbExistence) {

    suspend fun verify(contribution: CatalogContribution): ContributionVerdict {
        if (contribution.tmdbId <= 0) return ContributionVerdict.Rejected(ContributionVerdict.Reason.INVALID_TMDB_ID)

        val expected = ContentUuid.forTmdb(
            tmdbId = contribution.tmdbId,
            kind = contribution.kind,
            season = contribution.season,
            episode = contribution.episode,
        )
        if (expected != contribution.claimedContentUuid) {
            return ContributionVerdict.Rejected(ContributionVerdict.Reason.ADDRESS_MISMATCH)
        }

        // Only a definitive `false` rejects; `null` (unknown) leaves the structural verdict standing.
        if (tmdb.exists(contribution.tmdbId, contribution.kind) == false) {
            return ContributionVerdict.Rejected(ContributionVerdict.Reason.TMDB_NOT_FOUND)
        }

        return ContributionVerdict.Accepted
    }

    companion object {
        /** Bind the existence seam to a live [wtf.jobin.scanner.TmdbClient] (its tri-state probe). */
        fun overTmdbClient(client: wtf.jobin.scanner.TmdbClient): CatalogContributionValidator =
            CatalogContributionValidator { tmdbId, kind -> client.titleExists(tmdbId, kind) }
    }
}
