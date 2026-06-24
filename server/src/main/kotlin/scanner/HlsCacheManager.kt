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
 * Phase 15 (#80): bound the Hub HLS cache. Layout is {hlsRoot}/{libraryId}/{mediaId}/hls/.
 * LRU by the master playlist's mtime (touched on serve, see StreamRoutes). When total size
 * exceeds the cap, evict oldest media dirs until under — skipping `pin` (the media just
 * transcoded / currently wanted). On evict the dir is deleted and hls_path nulled, so the
 * next request lazily re-transcodes (#75).
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
        // {lib}/{media}/hls
        Files.newDirectoryStream(root).use { libs ->
            for (lib in libs) {
                if (!Files.isDirectory(lib)) continue
                Files.newDirectoryStream(lib).use { medias ->
                    for (m in medias) {
                        val hls = m.resolve("hls")
                        if (!Files.isDirectory(hls)) continue
                        val mediaId = runCatching { UUID.fromString(m.fileName.toString()) }.getOrNull() ?: continue
                        var size = 0L
                        Files.walk(hls).use { w -> w.filter { Files.isRegularFile(it) }.forEach { size += it.fileSize() } }
                        val mtime = runCatching { hls.resolve("playlist.m3u8").getLastModifiedTime().toMillis() }
                            .getOrDefault(0L)
                        entries.add(Entry(mediaId, hls, size, mtime))
                    }
                }
            }
        }
        var total = entries.sumOf { it.size }
        if (total <= maxBytes) return@withContext
        // oldest first; never evict the pinned (just-transcoded / active) media
        for (e in entries.filter { it.mediaId != pin }.sortedBy { it.mtime }) {
            if (total <= maxBytes) break
            e.dir.toFile().deleteRecursively()
            suspendTransaction(db) {
                MediaItems.update({ MediaItems.id eq e.mediaId }) {
                    it[hlsPath] = null
                    it[updatedAt] = Instant.now()
                }
            }
            total -= e.size
            log.info("evicted HLS cache for media {} ({} bytes)", e.mediaId, e.size)
        }
    }
}
