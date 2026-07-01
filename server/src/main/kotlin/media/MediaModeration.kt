package wtf.jobin.media

import org.jetbrains.exposed.v1.core.*
import wtf.jobin.db.MediaItems

/**
 * #128 (P2P / Self-Custody Re-architecture) — De-index-only moderation + TMDB allowlist.
 *
 * The gate is SPLIT by surface audience:
 *
 *  - OWNER surfaces (first-party clients browsing their own library): `/media`
 *    browse, the `/home` rows, and `/media/search`. Rule: de-index ONLY. An owner
 *    must keep seeing their own Titles even when there is no TMDB match — a missing
 *    match must never hide an owner's own media from them.
 *
 *  - PUBLIC / SHARED surface (the Stremio catalog — advertised outside the owner's
 *    first-party app). Rule: de-index AND the TMDB allowlist. A Title with no TMDB
 *    match is private-by-default and is never surfaced publicly.
 *
 * De-index HIDES; it never deletes. Direct-by-id detail (`/media/{id}`), playback,
 * Stremio stream/meta resolution and owner/admin tools intentionally apply NEITHER
 * gate — the caller already holds the id, so it is not a discovery surface.
 *
 * ponytail: one boolean column + two tiny predicates, deliberately NOT a moderation
 * subsystem. [isVisibleToOwner] and [isPubliclyIndexable] are the canonical,
 * unit-tested rules. Their SQL mirrors MUST stay in sync:
 *   - owner Exposed queries      -> [notDeindexedOp]
 *   - Stremio public Exposed      -> [publicCatalogOp]
 *   - raw BM25 search (owner)      -> WHERE `deindexed = false` in [MediaSearchService]
 * Upgrade path if moderation grows reason/actor/audit: a blocklist table keyed by
 * (tmdbId | mediaItemId) + a partial index. YAGNI until then.
 */
object MediaModeration {
    /** Owner surfaces: de-index only. A missing TMDB match does NOT hide owner media. */
    fun isVisibleToOwner(deindexed: Boolean): Boolean =
        !deindexed

    /** Public/shared (Stremio) surface: de-index AND the TMDB allowlist gate. */
    fun isPubliclyIndexable(deindexed: Boolean, tmdbId: Int?): Boolean =
        !deindexed && tmdbId != null
}

/**
 * Exposed predicate mirroring [MediaModeration.isVisibleToOwner] — de-index-only.
 * Used by the OWNER discovery surfaces (`/media` browse, `/home` rows). Compose with `and`:
 * `MediaItems.selectAll().where { MediaItems.backdrop.isNotNull() and notDeindexedOp() }`.
 */
fun notDeindexedOp(): Op<Boolean> =
    MediaItems.deindexed eq false

/**
 * Exposed predicate mirroring [MediaModeration.isPubliclyIndexable] — de-index AND
 * TMDB allowlist. Used ONLY by the public/shared Stremio catalog queries.
 */
fun publicCatalogOp(): Op<Boolean> =
    (MediaItems.deindexed eq false) and MediaItems.tmdbId.isNotNull()
