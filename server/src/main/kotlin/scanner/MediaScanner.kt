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
import wtf.jobin.db.Libraries
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

private val MEDIA_EXTS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "ts")

@Serializable
data class ScanResult(val added: Int, val removed: Int, val skipped: Int)

class MediaScanner(private val db: R2dbcDatabase, private val ffprobe: Ffprobe) {

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
            val now = Instant.now()
            suspendTransaction(db) {
                MediaItems.insert {
                    it[MediaItems.libraryId] = libraryId
                    it[MediaItems.title] = file.nameWithoutExtension
                    it[MediaItems.originalPath] = abs
                    it[MediaItems.durationSecs] = probe?.durationSecs
                    it[MediaItems.sizeBytes] = probe?.sizeBytes
                    it[MediaItems.mimeType] = probe?.mimeType
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

        return ScanResult(added, removed, skipped)
    }
}
