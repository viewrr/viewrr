package wtf.jobin.scanner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wtf.jobin.stremio.CapabilityProfile // #78
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 15 (#75): lazy transcode with stampede prevention. [ensure] transcodes a media's
 * HLS the first time it's requested; concurrent requests for the same media wait on a single
 * transcode instead of each launching their own. The on-disk playlist (and `hls_path`) is the
 * cache — once present, [ensure] is a no-op.
 *
 * ponytail: per-media Mutex in a ConcurrentHashMap; the map grows by distinct media id, which
 * is fine for a personal library. Add eviction only if that set gets large.
 */
class TranscodeCoordinator(
    private val transcoder: HlsTranscoder,
    private val cache: HlsCacheManager,
    // #95 (Phase 18): edge-cache pusher. NULL when media.edgeCacheEnabled is false (the default),
    // so the whole edge-cache mechanism is inert and ensure() behaves byte-identically to today.
    private val edgePusher: HlsEdgePusher? = null,
) {
    private val locks = ConcurrentHashMap<UUID, Mutex>()

    // #78: [profile] selects the targeted single rendition (null = today's full ladder). The
    // caller computes the profile-specific [playlist] path ({mediaId}/{profileKey}/hls/...), so
    // the existence checks here are per-(media,profile). The Mutex is still per-media: distinct
    // profiles for one media serialize (fine for a personal library) but never collide on disk.
    suspend fun ensure(mediaId: UUID, playlist: Path, profile: CapabilityProfile? = null) {
        if (Files.isRegularFile(playlist)) return
        var built = false // #95: did THIS call do the transcode (vs. someone winning the lock race)?
        locks.computeIfAbsent(mediaId) { Mutex() }.withLock {
            if (Files.isRegularFile(playlist)) return // built while we waited on the lock
            transcoder.transcode(mediaId, profile)
            built = true
        }
        // Phase 15 (#80): bound the cache after adding to it; pin the media we just built.
        cache.sweep(pin = mediaId)
        // #95 (Phase 18): edge-cache the freshly-built bundle to the owning same-LAN node. Only when
        // edge-cache is enabled (edgePusher != null) AND we actually built it here. Best-effort: any
        // failure inside is swallowed so it can never break the just-served Hub playlist.
        // TODO needs node deployed: real push only succeeds against a running second Node.
        val pusher = edgePusher
        if (built && pusher != null) {
            runCatching { pusher.pushIfEligible(mediaId, playlist) }
        }
    }
}
