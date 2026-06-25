package wtf.jobin.streaming

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import wtf.jobin.db.MediaItems
import java.nio.file.Path
import java.util.UUID

// #94 (Phase 18): next-episode prefetch. When a device starts playing (the master playlist is
// served via the #75 lazy path), the Hub warm-transcodes the NEXT item to the SAME capability
// profile so playback continues without a cold start.
//
// ponytail: single next-item warm, fire-and-forget, no queue. Movie (showTitle == null) has no
// successor → skip. Music is a separate table/path → out of scope. Multi-ahead lookahead and
// music prefetch are noted follow-ups, intentionally not implemented here.
// #94: shared logger — also used by the StreamRoutes call site to report swallowed failures.
internal val prefetchLog = LoggerFactory.getLogger("wtf.jobin.streaming.Prefetch")

// #94: a TV item's ordering coordinates, used to find its successor within the same showTitle.
private data class EpisodeRef(
    val mediaId: UUID,
    val libraryId: UUID,
    val showTitle: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
)

/**
 * #94: Warm-transcode the next episode of [mediaId] at [profile] (depth +1, background-priority,
 * fire-and-forget). Resolves the successor by (seasonNumber, episodeNumber) within the same
 * showTitle, crossing the season boundary (S1 last → S2E1) when one exists. No-ops for movies
 * (no showTitle) and when no successor exists. The next playlist path is computed exactly as
 * serveHlsFile computes the current target so the warm hits the same on-disk dir the player
 * will request. coordinator.ensure dedups and no-ops if already transcoded.
 *
 * ponytail: never blocks or fails the caller — the call site wraps this in runCatching and
 * launches it in the background, and DB/FS errors here are logged + swallowed.
 */
internal suspend fun prefetchNextEpisode(
    db: R2dbcDatabase,
    hlsRoot: Path,
    mediaId: UUID,
    profile: wtf.jobin.stremio.CapabilityProfile?,
    coordinator: wtf.jobin.scanner.TranscodeCoordinator,
) {
    // Read the current item's series coordinates. Movie (showTitle/season/episode null) → skip.
    val current = suspendTransaction(db) {
        MediaItems.selectAll()
            .where { MediaItems.id eq mediaId }
            .map {
                val show = it[MediaItems.showTitle]
                val season = it[MediaItems.seasonNumber]
                val episode = it[MediaItems.episodeNumber]
                if (show == null || season == null || episode == null) {
                    null
                } else {
                    EpisodeRef(it[MediaItems.id].value, it[MediaItems.libraryId].value, show, season, episode)
                }
            }
            .firstOrNull()
    } ?: return // no row, or a non-episodic item (movie) → nothing to prefetch

    val next = findNext(db, current) ?: return // last episode of the series → nothing ahead

    // Compute the next playlist path identically to serveHlsFile's target construction:
    // hlsRoot/{libraryId}/{mediaId}/{profileKey}/hls/playlist.m3u8.
    val profileKey = wtf.jobin.stremio.profileKeyOf(profile)
    val nextPlaylist = hlsRoot
        .resolve(next.libraryId.toString())
        .resolve(next.mediaId.toString())
        .resolve(profileKey)
        .resolve("hls")
        .resolve("playlist.m3u8")

    // Warm via the same coordinator as #75 lazy: dedups per-(media,profile), no-ops if present.
    coordinator.ensure(next.mediaId, nextPlaylist, profile)
    prefetchLog.debug("#94 prefetch: warmed next episode {} (profile {})", next.mediaId, profileKey)
}

/**
 * #94: The successor episode of [current] within the same showTitle. First tries the next episode
 * in the same season (smallest episodeNumber > current within current.season). If none, crosses
 * into the next season (smallest seasonNumber > current.season, then its smallest episodeNumber).
 * Returns null when [current] is the last episode of the series.
 */
private suspend fun findNext(db: R2dbcDatabase, current: EpisodeRef): EpisodeRef? = suspendTransaction(db) {
    fun row(it: ResultRow): EpisodeRef? {
        val show = it[MediaItems.showTitle] ?: return null
        val season = it[MediaItems.seasonNumber] ?: return null
        val episode = it[MediaItems.episodeNumber] ?: return null
        return EpisodeRef(it[MediaItems.id].value, it[MediaItems.libraryId].value, show, season, episode)
    }

    // Same-season successor: same show, same season, episode > current, ordered ascending.
    val sameSeason = MediaItems.selectAll()
        .where {
            (MediaItems.showTitle eq current.showTitle) and
                (MediaItems.seasonNumber eq current.seasonNumber) and
                (MediaItems.episodeNumber greater current.episodeNumber)
        }
        .orderBy(MediaItems.episodeNumber to SortOrder.ASC)
        .map { row(it) }
        .firstOrNull { it != null }
    if (sameSeason != null) return@suspendTransaction sameSeason

    // Cross the season boundary: smallest season > current, then its smallest episode.
    MediaItems.selectAll()
        .where {
            (MediaItems.showTitle eq current.showTitle) and
                (MediaItems.seasonNumber greater current.seasonNumber)
        }
        .orderBy(MediaItems.seasonNumber to SortOrder.ASC, MediaItems.episodeNumber to SortOrder.ASC)
        .map { row(it) }
        .firstOrNull { it != null }
}
