package wtf.jobin.media

import org.jetbrains.exposed.v1.core.*
import wtf.jobin.db.MediaItems

/**
 * #128 (P2P / Self-Custody Re-architecture) — De-index-only moderation + TMDB allowlist.
 *
 * A Title is "publicly indexable" — eligible to appear in the central catalog's
 * DISCOVERY surfaces (browse `/media`, `/media/search`, the `/home` rows, Stremio catalog) —
 * only when BOTH hold:
 *   1. it has NOT been operator de-indexed (`media_items.deindexed = false`), and
 *   2. it has a TMDB match (`tmdb_id IS NOT NULL`) — the allowlist gate; a Title
 *      with no tmdbId is private-by-default and is never surfaced.
 *
 * De-index HIDES; it never deletes. Direct-by-id detail (`/media/{id}`), playback,
 * Stremio stream/meta resolution and owner/admin tools intentionally do NOT apply
 * this gate — the caller already holds the id, so it is not a discovery surface.
 *
 * ponytail: one boolean column + one reused predicate — deliberately NOT a
 * moderation subsystem. [isPubliclyIndexable] is the canonical, unit-tested rule;
 * [publicCatalogOp] is its SQL mirror used by the Exposed discovery queries, and
 * the raw BM25 WHERE clause in [MediaSearchService] mirrors it too — all three MUST
 * stay in sync. Upgrade path if moderation grows reason/actor/audit: a blocklist
 * table keyed by (tmdbId | mediaItemId) + a partial index. YAGNI until then.
 */
object MediaModeration {
    /** Canonical visibility rule. The SQL surfaces mirror exactly this. */
    fun isPubliclyIndexable(deindexed: Boolean, tmdbId: Int?): Boolean =
        !deindexed && tmdbId != null
}

/**
 * Exposed predicate mirroring [MediaModeration.isPubliclyIndexable], for query-side
 * (in-DB) filtering of the public discovery surfaces. Compose with `and`:
 * `MediaItems.selectAll().where { MediaItems.showTitle.isNull() and publicCatalogOp() }`.
 */
fun publicCatalogOp(): Op<Boolean> =
    (MediaItems.deindexed eq false) and MediaItems.tmdbId.isNotNull()
