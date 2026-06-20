package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class HlsTranscoder(
    private val db: R2dbcDatabase,
    private val ffmpegPath: String,
    private val hlsRoot: String,
) {
    /**
     * Transcode the media item's source file to a single-variant HLS playlist.
     * Returns the absolute path to `playlist.m3u8` and updates `media_items.hls_path`.
     * Throws if ffmpeg exits non-zero; message includes the tail of merged stdout+stderr.
     */
    suspend fun transcode(mediaId: UUID): Path {
        val (libraryId, inputPath) = suspendTransaction(db) {
            MediaItems.select(MediaItems.libraryId, MediaItems.originalPath)
                .where { MediaItems.id eq mediaId }
                .map { it[MediaItems.libraryId].value to it[MediaItems.originalPath] }
                .firstOrNull()
        } ?: error("media item $mediaId not found")

        val outDir = Path.of(hlsRoot, libraryId.toString(), mediaId.toString(), "hls")
        val playlist = outDir.resolve("playlist.m3u8")

        withContext(Dispatchers.IO) {
            Files.createDirectories(outDir)
            val proc = ProcessBuilder(
                ffmpegPath,
                "-y",
                "-i", inputPath,
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "23",
                "-c:a", "aac",
                "-b:a", "128k",
                "-hls_time", "6",
                "-hls_playlist_type", "vod",
                "-hls_segment_filename", outDir.resolve("segment_%03d.ts").toString(),
                playlist.toString(),
            ).redirectErrorStream(true).start()
            // ponytail: merged stream, drained in one read — ffmpeg error logs fit easily.
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) {
                error("ffmpeg failed for media $mediaId (exit ${proc.exitValue()}):\n${out.takeLast(2000)}")
            }
        }

        val absPlaylist = playlist.toAbsolutePath()
        suspendTransaction(db) {
            MediaItems.update({ MediaItems.id eq mediaId }) {
                it[hlsPath] = absPlaylist.toString()
                it[updatedAt] = Instant.now()
            }
        }
        return absPlaylist
    }
}
