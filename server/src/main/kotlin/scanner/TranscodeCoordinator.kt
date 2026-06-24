package wtf.jobin.scanner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    private val locks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun ensure(mediaId: UUID, playlist: Path) {
        if (Files.isRegularFile(playlist)) return
        locks.computeIfAbsent(mediaId) { Mutex() }.withLock {
            if (Files.isRegularFile(playlist)) return // built while we waited on the lock
            transcoder.transcode(mediaId)
        }
        // Phase 15 (#80): bound the cache after adding to it; pin the media we just built.
        cache.sweep(pin = mediaId)
    }
}
