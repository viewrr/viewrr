package wtf.jobin.trickplay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.ceil

@Serializable
data class TrickplayInfo(
    val spriteUrl: String,
    val vttUrl: String,
    val intervalSecs: Int,
    val thumbW: Int,
    val thumbH: Int,
    val tileCols: Int,
)

class TrickplayGenerator(
    private val ffmpegPath: String,
    private val hlsRoot: String,
    private val db: R2dbcDatabase,
) {
    private companion object {
        const val INTERVAL = 10
        const val THUMB_W = 160
        const val THUMB_H = 90
        const val COLS = 10
        const val MAX_THUMBS = 100
    }

    /**
     * Lazily generate (or reuse) the seek-preview assets for [mediaId]: one sprite sheet
     * (`trickplay.jpg`) plus the WebVTT (`trickplay.vtt`) that maps each 10s interval to a
     * tile region. Returns null when the media item doesn't exist.
     */
    suspend fun ensure(mediaId: UUID): TrickplayInfo? {
        val row = suspendTransaction(db) {
            MediaItems.select(MediaItems.libraryId, MediaItems.originalPath, MediaItems.durationSecs)
                .where { MediaItems.id eq mediaId }
                .map { Triple(it[MediaItems.libraryId].value, it[MediaItems.originalPath], it[MediaItems.durationSecs]) }
                .firstOrNull()
        } ?: return null
        val (libraryId, inputPath, duration) = row

        val outDir = Path.of(hlsRoot, libraryId.toString(), mediaId.toString(), "hls")
        val sprite = outDir.resolve("trickplay.jpg")
        val vtt = outDir.resolve("trickplay.vtt")

        val ready = withContext(Dispatchers.IO) {
            Files.createDirectories(outDir)
            // Cache hit: both assets present → nothing to regenerate.
            if (Files.isRegularFile(sprite) && Files.isRegularFile(vtt)) return@withContext true
            runFfmpeg(inputPath, outDir, mediaId)
            // Content shorter than one interval yields no frames; ffmpeg exits 0 but writes
            // no sprite. No scrub preview is possible → signal "no trickplay" to the caller.
            if (!Files.isRegularFile(sprite)) return@withContext false
            val n = (duration?.let { minOf(ceil(it / INTERVAL.toDouble()).toInt(), MAX_THUMBS) } ?: 1)
                .coerceAtLeast(1)
            Files.writeString(vtt, buildVtt(n))
            true
        }
        if (!ready) return null

        return TrickplayInfo(
            spriteUrl = "/stream/$mediaId/trickplay.jpg",
            vttUrl = "/stream/$mediaId/trickplay.vtt",
            intervalSecs = INTERVAL,
            thumbW = THUMB_W,
            thumbH = THUMB_H,
            tileCols = COLS,
        )
    }

    // ponytail: single 10x10 sheet (<=100 thumbs ~16min @10s); multi-sheet only if long content matters.
    private fun runFfmpeg(inputPath: String, outDir: Path, mediaId: UUID) {
        val args = listOf(
            ffmpegPath, "-y", "-i", inputPath,
            // format=yuvj420p: mjpeg rejects full-range yuv444p sources ("Error while opening encoder").
            "-vf", "fps=1/$INTERVAL,scale=$THUMB_W:$THUMB_H,tile=${COLS}x${COLS},format=yuvj420p",
            "-frames:v", "1", "-an", "trickplay.jpg",
        )
        // cwd = outDir so `trickplay.jpg` lands in the flat hls dir the /stream route serves.
        val proc = ProcessBuilder(args)
            .directory(outDir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            error("ffmpeg trickplay failed for media $mediaId (exit ${proc.exitValue()}):\n${out.takeLast(2000)}")
        }
    }

    private fun buildVtt(n: Int): String {
        val sb = StringBuilder("WEBVTT\n\n")
        for (i in 0 until n) {
            val start = i * INTERVAL
            val end = (i + 1) * INTERVAL
            val x = (i % COLS) * THUMB_W
            val y = (i / COLS) * THUMB_H
            sb.append("${formatTs(start)} --> ${formatTs(end)}\n")
            sb.append("trickplay.jpg#xywh=$x,$y,$THUMB_W,$THUMB_H\n\n")
        }
        return sb.toString()
    }

    /** Seconds → `HH:MM:SS.000` WebVTT cue timestamp. */
    private fun formatTs(secs: Int): String =
        "%02d:%02d:%02d.000".format(secs / 3600, (secs % 3600) / 60, secs % 60)
}
