package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

/**
 * Phase 15 (#80): bound the Hub HLS cache. Layout is {hlsRoot}/{libraryId}/{mediaId}/{profileKey}/hls/
 * (#78: profile-aware; profileKey == "default" for the no-profile case). LRU by the master
 * playlist's mtime (touched on serve, see StreamRoutes). When total size exceeds the cap, evict
 * oldest profile dirs until under — skipping any dir whose media == `pin` (the media just
 * transcoded / currently wanted). On evict the {profileKey}/hls dir is deleted; hls_path is nulled
 * only once ALL of a media's profile dirs are gone, so the next request lazily re-transcodes (#75).
 *
 * ponytail: sweep runs after each transcode (no scheduler). Single pass, whole-tree walk —
 * fine for a personal-scale cache; add incremental accounting only if the tree gets huge.
 */
class HlsCacheManager(
    private val hlsRoot: Path,
    private val db: R2dbcDatabase,
    private val maxBytes: Long,
) {
    private val log = LoggerFactory.getLogger("wtf.jobin.scanner.HlsCacheManager")

    private data class Entry(val mediaId: UUID, val dir: Path, val size: Long, val mtime: Long)

    suspend fun sweep(pin: UUID?) = withContext(Dispatchers.IO) {
        val root = hlsRoot
        if (!Files.isDirectory(root)) return@withContext
        val entries = mutableListOf<Entry>()
        // #78: one Entry per profile dir — {lib}/{media}/{profileKey}/hls
        // remaining counts each media's live profile dirs so we only null hls_path when the
        // last one is evicted.
        val remaining = HashMap<UUID, Int>()
        Files.newDirectoryStream(root).use { libs ->
            for (lib in libs) {
                if (!Files.isDirectory(lib)) continue
                Files.newDirectoryStream(lib).use { medias ->
                    for (m in medias) {
                        val mediaId = runCatching { UUID.fromString(m.fileName.toString()) }.getOrNull() ?: continue
                        if (!Files.isDirectory(m)) continue
                        // #78: iterate the {profileKey} dirs under each media.
                        Files.newDirectoryStream(m).use { profiles ->
                            for (p in profiles) {
                                val hls = p.resolve("hls")
                                if (!Files.isDirectory(hls)) continue
                                var size = 0L
                                Files.walk(hls).use { w -> w.filter { Files.isRegularFile(it) }.forEach { size += it.fileSize() } }
                                val mtime = runCatching { hls.resolve("playlist.m3u8").getLastModifiedTime().toMillis() }
                                    .getOrDefault(0L)
                                // dir = the {profileKey} dir so eviction removes the whole profile build.
                                entries.add(Entry(mediaId, p, size, mtime))
                                remaining[mediaId] = (remaining[mediaId] ?: 0) + 1
                            }
                        }
                    }
                }
            }
        }
        var total = entries.sumOf { it.size }
        if (total <= maxBytes) return@withContext
        // oldest first; never evict a dir belonging to the pinned (just-transcoded / active) media
        for (e in entries.filter { it.mediaId != pin }.sortedBy { it.mtime }) {
            if (total <= maxBytes) break
            e.dir.toFile().deleteRecursively()
            // #78: only null hls_path once this media's LAST profile dir is gone; otherwise other
            // profiles still have valid on-disk builds (and the serve path checks the file directly).
            val left = (remaining[e.mediaId] ?: 1) - 1
            remaining[e.mediaId] = left
            if (left <= 0) {
                suspendTransaction(db) {
                    MediaItems.update({ MediaItems.id eq e.mediaId }) {
                        it[hlsPath] = null
                        it[updatedAt] = Instant.now()
                    }
                }
            }
            total -= e.size
            log.info("evicted HLS cache dir {} for media {} ({} bytes)", e.dir.fileName, e.mediaId, e.size)
        }
    }
}
