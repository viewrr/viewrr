package wtf.jobin.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Downloads
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class Mp4Downloader(
    private val db: R2dbcDatabase,
    private val ffmpegPath: String,
    private val downloadsRoot: String,
) {
    /**
     * Idempotently transcode the media item's source to a single faststart MP4
     * under `{downloadsRoot}/{userId}/{mediaId}/{deviceId}.mp4` and upsert a
     * `downloads` row with status='ready', file_path=<abs>, expires_at=now+7d.
     * Returns the absolute output path. If the file already exists, the upsert
     * still runs to refresh status and expiry.
     */
    suspend fun prepare(mediaId: UUID, userId: UUID, deviceId: String): Path {
        val inputPath = suspendTransaction(db) {
            MediaItems.select(MediaItems.originalPath)
                .where { MediaItems.id eq mediaId }
                .map { it[MediaItems.originalPath] }
                .firstOrNull()
        } ?: error("media item $mediaId not found")

        val outDir = Path.of(downloadsRoot, userId.toString(), mediaId.toString())
        val outFile = outDir.resolve("$deviceId.mp4")

        withContext(Dispatchers.IO) {
            Files.createDirectories(outDir)
            if (!Files.exists(outFile)) {
                val proc = ProcessBuilder(
                    ffmpegPath,
                    "-y",
                    "-i", inputPath,
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    outFile.toString(),
                ).redirectErrorStream(true).start()
                // ponytail: merged stream, drained in one read — ffmpeg error logs fit easily.
                val out = proc.inputStream.bufferedReader().readText()
                if (proc.waitFor() != 0) {
                    error("ffmpeg failed for media $mediaId device $deviceId (exit ${proc.exitValue()}):\n${out.takeLast(2000)}")
                }
            }
        }

        val absOut = outFile.toAbsolutePath()
        val now = Instant.now()
        val expires = now.plus(7, ChronoUnit.DAYS)
        suspendTransaction(db) {
            Downloads.upsert(
                Downloads.userId, Downloads.mediaId, Downloads.deviceId,
                onUpdate = {
                    it[Downloads.status] = "ready"
                    it[Downloads.filePath] = absOut.toString()
                    it[Downloads.expiresAt] = expires
                },
            ) {
                it[Downloads.userId] = userId
                it[Downloads.mediaId] = mediaId
                it[Downloads.deviceId] = deviceId
                it[Downloads.status] = "ready"
                it[Downloads.filePath] = absOut.toString()
                it[Downloads.expiresAt] = expires
                it[Downloads.createdAt] = now
            }
        }
        return absOut
    }
}
