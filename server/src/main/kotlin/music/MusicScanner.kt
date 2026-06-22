package wtf.jobin.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Libraries
import wtf.jobin.db.MusicTracks
import wtf.jobin.scanner.ScanResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

private val AUDIO_EXTS = setOf("mp3", "flac", "m4a", "wav", "aac", "ogg", "opus")

/** Mirrors [wtf.jobin.scanner.MediaScanner] for audio: probe tags, index, prune. No transcode. */
class MusicScanner(
    private val db: R2dbcDatabase,
    private val probe: MusicProbe,
) {
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
                stream.filter { Files.isRegularFile(it) && it.extension.lowercase() in AUDIO_EXTS }
                    .toList()
            }
        }

        val onDiskPaths = onDisk.map { it.toAbsolutePath().toString() }.toSet()
        val existingPaths: Set<String> = suspendTransaction(db) {
            MusicTracks.select(MusicTracks.originalPath)
                .where { MusicTracks.libraryId eq libraryId }
                .map { it[MusicTracks.originalPath] }
                .toList()
                .toSet()
        }

        var added = 0
        var skipped = 0
        for (file in onDisk) {
            val abs = file.toAbsolutePath().toString()
            if (abs in existingPaths) { skipped++; continue }
            val meta = probe.probe(file)
            val now = Instant.now()
            suspendTransaction(db) {
                MusicTracks.insert {
                    it[MusicTracks.libraryId] = libraryId
                    it[MusicTracks.title] = meta?.title ?: file.nameWithoutExtension
                    it[MusicTracks.artist] = meta?.artist
                    it[MusicTracks.album] = meta?.album
                    it[MusicTracks.albumArtist] = meta?.albumArtist
                    it[MusicTracks.trackNumber] = meta?.trackNumber
                    it[MusicTracks.discNumber] = meta?.discNumber
                    it[MusicTracks.durationSecs] = meta?.durationSecs
                    it[MusicTracks.originalPath] = abs
                    it[MusicTracks.mimeType] = meta?.mimeType
                    it[MusicTracks.createdAt] = now
                    it[MusicTracks.updatedAt] = now
                }
            }
            added++
        }

        val removed = suspendTransaction(db) {
            MusicTracks.deleteWhere {
                (MusicTracks.libraryId eq libraryId) and (MusicTracks.originalPath notInList onDiskPaths)
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
