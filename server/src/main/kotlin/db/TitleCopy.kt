package wtf.jobin.db

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.cluster.nodeOnline // #85
import java.time.Instant
import java.util.UUID

// #82 (ADR-0002): Title/Copy split helpers, shared by both ingest paths
// (cluster/AgentRoutes /agent/media and scanner/MediaScanner) plus copy
// resolution for transcode. media_items is the logical Title; media_copies is
// the physical file layer.
//
// ponytail: matching key is tmdbId when present, else (cleanTitle, year). Music
// (tags/MusicBrainz) and content-hash fallback are out of scope here — movies/TV
// only, mirroring the rest of the scan path today.

/** #82: the Title-level metadata used to find-or-create a media_items row. */
data class TitleSpec(
    val libraryId: UUID,
    val title: String,
    val cleanTitle: String? = null,
    val showTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val durationSecs: Int? = null,
    // Legacy physical columns kept on media_items for single-copy compat (V13 / #85).
    // The first Copy seeds them so today's transcode/playback fallback is unchanged.
    val nodeId: UUID,
    val originalPath: String,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
)

/** #82: the physical Copy fields for a single file on a node. */
data class CopySpec(
    val nodeId: UUID,
    val originalPath: String,
    val sizeBytes: Long? = null,
    val codecs: String? = null,
    val hlsPath: String? = null,
)

/**
 * #82: outcome of [findOrCreateTitle] — the Title id and whether a new row was
 * created (true) or an existing Title was matched (false), so callers can keep
 * their added/updated counters meaningful.
 */
data class TitleResult(val id: UUID, val created: Boolean)

/**
 * #82: find an existing Title or create one. Match precedence:
 *   1. tmdbId, when [TitleSpec.tmdbId] is non-null (movie/TV primary key);
 *   2. (cleanTitle, year) otherwise.
 * A second node reporting the same movie therefore attaches a new Copy under the
 * SAME media_items row rather than creating a duplicate Title.
 *
 * ponytail: when no match key is usable (no tmdbId AND no cleanTitle) we always
 * create — we can't safely dedup an untitled probe-only record, and the legacy
 * single-box path never deduped at all, so this is strictly additive.
 */
suspend fun findOrCreateTitle(db: R2dbcDatabase, spec: TitleSpec): TitleResult {
    val existing: UUID? = suspendTransaction(db) {
        when {
            spec.tmdbId != null ->
                MediaItems.select(MediaItems.id)
                    .where { MediaItems.tmdbId eq spec.tmdbId }
                    .map { it[MediaItems.id].value }
                    .firstOrNull()
            spec.cleanTitle != null ->
                MediaItems.select(MediaItems.id)
                    .where {
                        (MediaItems.cleanTitle eq spec.cleanTitle) and
                            (MediaItems.year eq spec.year?.toShort())
                    }
                    .map { it[MediaItems.id].value }
                    .firstOrNull()
            else -> null
        }
    }
    if (existing != null) return TitleResult(existing, created = false)

    val now = Instant.now()
    val id = suspendTransaction(db) {
        MediaItems.insertAndGetId {
            it[MediaItems.libraryId] = spec.libraryId
            it[MediaItems.nodeId] = spec.nodeId
            it[MediaItems.title] = spec.title
            it[MediaItems.cleanTitle] = spec.cleanTitle
            it[MediaItems.showTitle] = spec.showTitle
            it[MediaItems.seasonNumber] = spec.seasonNumber
            it[MediaItems.episodeNumber] = spec.episodeNumber
            it[MediaItems.year] = spec.year?.toShort()
            it[MediaItems.tmdbId] = spec.tmdbId
            it[MediaItems.poster] = spec.poster
            it[MediaItems.backdrop] = spec.backdrop
            it[MediaItems.overview] = spec.overview
            it[MediaItems.originalPath] = spec.originalPath
            it[MediaItems.durationSecs] = spec.durationSecs
            it[MediaItems.sizeBytes] = spec.sizeBytes
            it[MediaItems.mimeType] = spec.mimeType
            it[MediaItems.createdAt] = now
            it[MediaItems.updatedAt] = now
        }.value
    }
    return TitleResult(id, created = true)
}

/**
 * #82: insert-or-update the Copy for [spec]'s (node, path) under [titleId].
 * Returns true when a new Copy row was created, false when an existing one was
 * updated (keyed by the (node_id, original_path) unique constraint).
 */
suspend fun upsertCopy(db: R2dbcDatabase, titleId: UUID, spec: CopySpec): Boolean {
    val now = Instant.now()
    val existing: UUID? = suspendTransaction(db) {
        MediaCopies.select(MediaCopies.id)
            .where {
                (MediaCopies.nodeId eq spec.nodeId) and
                    (MediaCopies.originalPath eq spec.originalPath)
            }
            .map { it[MediaCopies.id].value }
            .firstOrNull()
    }
    if (existing != null) {
        suspendTransaction(db) {
            MediaCopies.update({ MediaCopies.id eq existing }) {
                it[MediaCopies.titleId] = titleId
                it[MediaCopies.sizeBytes] = spec.sizeBytes
                it[MediaCopies.codecs] = spec.codecs
                if (spec.hlsPath != null) it[MediaCopies.hlsPath] = spec.hlsPath
                it[MediaCopies.updatedAt] = now
            }
        }
        return false
    }
    suspendTransaction(db) {
        MediaCopies.insert {
            it[MediaCopies.titleId] = titleId
            it[MediaCopies.nodeId] = spec.nodeId
            it[MediaCopies.originalPath] = spec.originalPath
            it[MediaCopies.sizeBytes] = spec.sizeBytes
            it[MediaCopies.codecs] = spec.codecs
            it[MediaCopies.hlsPath] = spec.hlsPath
            it[MediaCopies.createdAt] = now
            it[MediaCopies.updatedAt] = now
        }
    }
    return true
}

/** #82: the (node, path) of a chosen physical Copy for a Title. */
data class ResolvedCopy(val nodeId: UUID, val originalPath: String)

/**
 * #82/#85: pick a physical Copy for [titleId] (node + path) that lives on an *online*
 * node, or null if the Title has no online Copy. Online = the copy's node is
 * LOCAL_NODE_ID (the hub itself — no heartbeat, but it's up if it's serving) OR the
 * node heartbeated within the window (see [nodeOnline] / #83). Among online copies we
 * pick the oldest (deterministic, matches #82's createdAt ordering).
 *
 * #85: returning null when no copy is online is intentional — it's the signal the
 * Title is currently unplayable (offline). Callers KEEP a media_items fallback for the
 * single-copy case (today) so that when V13 has backfilled exactly one Copy mirroring
 * the legacy columns AND that copy is online (LOCAL or fresh heartbeat), the resolved
 * (nodeId, originalPath) is byte-identical to before. ponytail: single-box installs all
 * sit on LOCAL_NODE_ID, which is always-online, so they never regress.
 */
suspend fun resolveCopy(db: R2dbcDatabase, titleId: UUID): ResolvedCopy? {
    val now = Instant.now()
    // #85: join media_copies -> nodes so we can filter by online-ness. lastSeenAt is null
    // for never-seen / heartbeat-less nodes; LOCAL_NODE_ID is forced online regardless.
    val rows = suspendTransaction(db) {
        (MediaCopies leftJoin Nodes)
            .select(MediaCopies.nodeId, MediaCopies.originalPath, MediaCopies.createdAt, Nodes.lastSeenAt)
            .where { MediaCopies.titleId eq titleId }
            .orderBy(MediaCopies.createdAt)
            .map {
                Triple(
                    ResolvedCopy(it[MediaCopies.nodeId].value, it[MediaCopies.originalPath]),
                    it[MediaCopies.createdAt],
                    it[Nodes.lastSeenAt],
                )
            }
            .toList()
    }
    // #85: prefer online (LOCAL always-online OR fresh heartbeat); oldest wins (rows already
    // ordered by createdAt). No online copy -> null (Title is offline / unplayable).
    return rows.firstOrNull { (copy, _, lastSeen) ->
        copy.nodeId == LOCAL_NODE_ID || nodeOnline(lastSeen, now)
    }?.first
}

/**
 * #85: true when [titleId] has at least one Copy on an online node (same online rule as
 * [resolveCopy]: LOCAL_NODE_ID always-online, else heartbeat within window). Drives
 * availability (#84): a Title with no online copy is listed but not playable. Equivalent
 * to `resolveCopy(db, titleId) != null`; provided as a dedicated boolean for catalog/meta
 * callers that only need availability, not the (node, path).
 */
suspend fun hasOnlineCopy(db: R2dbcDatabase, titleId: UUID): Boolean = resolveCopy(db, titleId) != null
