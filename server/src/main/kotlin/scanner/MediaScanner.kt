package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import wtf.jobin.db.Libraries
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

private val log = LoggerFactory.getLogger("wtf.jobin.scanner.MediaScanner")

@Serializable
data class ScanResult(val added: Int, val removed: Int, val skipped: Int)

class MediaScanner(
    private val db: R2dbcDatabase,
    private val ffprobe: Ffprobe,
    private val tmdb: TmdbClient,
) {

    // Phase 15 (#75): scan only indexes. Transcode is lazy — triggered on first stream
    // request (see TranscodeCoordinator), so a scan no longer transcodes the whole library.
    suspend fun scan(libraryId: UUID): ScanResult {
        val rootPath = suspendTransaction(db) {
            Libraries.selectAll()
                .where { Libraries.id eq libraryId }
                .map { it[Libraries.rootPath] }
                .firstOrNull()
        } ?: error("library $libraryId not found")

        val root = Path.of(rootPath)
        require(Files.isDirectory(root)) { "library root not a directory: $rootPath" }

        val onDisk = withContext(Dispatchers.IO) {
            Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.extension.lowercase() in MEDIA_EXTS }
                    .toList()
            }
        }

        val onDiskPaths = onDisk.map { it.toAbsolutePath().toString() }.toSet()
        val existingPaths: Set<String> = suspendTransaction(db) {
            MediaItems.select(MediaItems.originalPath)
                .where { MediaItems.libraryId eq libraryId }
                .map { it[MediaItems.originalPath] }
                .toList()
                .toSet()
        }

        var added = 0
        var skipped = 0
        for (file in onDisk) {
            val abs = file.toAbsolutePath().toString()
            if (abs in existingPaths) { skipped++; continue }
            val probe = ffprobe.probe(file)
            // ponytail: ffprobe is the source of truth for "is this actually playable video";
            // the extension is just a fast prefilter.
            if (probe == null || !probe.hasVideo) {
                skipped++
                log.info("skip non-video file: {}", abs)
                continue
            }
            val parsed = FilenameParser.parse(file.nameWithoutExtension)
            // ponytail: movies only, lookup inline in the scan loop (one HTTP GET per new
            // movie). Fine for personal libraries; move to the async block below or a worker
            // queue if scans grow large or TMDb rate-limits bite. Shows: parse showTitle != null.
            val meta = if (parsed.showTitle == null) tmdb.lookupMovie(parsed.cleanTitle, parsed.year) else null
            val now = Instant.now()
            suspendTransaction(db) {
                MediaItems.insertAndGetId {
                    it[MediaItems.libraryId] = libraryId
                    it[MediaItems.nodeId] = wtf.jobin.db.LOCAL_NODE_ID // Phase 14 (#72)
                    it[MediaItems.title] = file.nameWithoutExtension
                    it[MediaItems.cleanTitle] = parsed.cleanTitle
                    it[MediaItems.showTitle] = parsed.showTitle
                    it[MediaItems.seasonNumber] = parsed.seasonNumber
                    it[MediaItems.episodeNumber] = parsed.episodeNumber
                    it[MediaItems.year] = parsed.year?.toShort()
                    it[MediaItems.tmdbId] = meta?.tmdbId
                    it[MediaItems.poster] = meta?.poster
                    it[MediaItems.backdrop] = meta?.backdrop
                    it[MediaItems.overview] = meta?.overview
                    it[MediaItems.originalPath] = abs
                    it[MediaItems.durationSecs] = probe.durationSecs
                    it[MediaItems.sizeBytes] = probe.sizeBytes
                    it[MediaItems.mimeType] = probe.mimeType
                    it[MediaItems.createdAt] = now
                    it[MediaItems.updatedAt] = now
                }
            }
            added++
        }

        val removed = suspendTransaction(db) {
            MediaItems.deleteWhere {
                (MediaItems.libraryId eq libraryId) and (MediaItems.originalPath notInList onDiskPaths)
            }
        }

        suspendTransaction(db) {
            Libraries.update({ Libraries.id eq libraryId }) {
                it[Libraries.lastScannedAt] = Instant.now()
            }
        }

        return ScanResult(added, removed, skipped)
    }
}
