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
    private val ffprobePath: String,
    private val hlsRoot: String,
) {
    /**
     * Transcode the media item's source file to an adaptive-bitrate HLS ladder:
     * one variant playlist per rendition plus a master `playlist.m3u8`.
     * Returns the absolute path to `playlist.m3u8` and updates `media_items.hls_path`.
     * Throws if any ffmpeg pass exits non-zero; message includes the tail of merged stdout+stderr.
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
            val renditions = buildRenditions(probeDimensions(inputPath))
            for (r in renditions) {
                runFfmpeg(inputPath, outDir, r, mediaId)
            }
            Files.writeString(playlist, masterPlaylist(renditions))
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

    /**
     * One ABR rendition. [w]/[h] are null only when the source probe failed, in which case
     * [scaleFilter] is null and ffmpeg encodes at the source's native resolution.
     */
    private data class Rendition(
        val name: String,
        val w: Int?,
        val h: Int?,
        val br: Int,
        val scaleFilter: String?,
    )

    /** `ffprobe ... -of csv=s=x:p=0` → e.g. `1920x1080`. Returns null if it fails or is unparseable. */
    private fun probeDimensions(inputPath: String): Pair<Int, Int>? {
        val proc = ProcessBuilder(
            ffprobePath, "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-of", "csv=s=x:p=0",
            inputPath,
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() != 0) return null
        val parts = out.split("x")
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    // ponytail: renditions are downscale-only (never upscale the source) and capped at 3.
    private fun buildRenditions(dims: Pair<Int, Int>?): List<Rendition> {
        if (dims == null) {
            // ponytail: probe failed — single rendition at native resolution, no scaling.
            return listOf(Rendition(name = "vsrc", w = null, h = null, br = 2800, scaleFilter = null))
        }
        val (srcW, srcH) = dims
        val heights = (listOf(1080, 720, 480, 360).filter { it < srcH } + srcH).distinct().sortedDescending().take(3)
        return heights.map { h ->
            val w = ((srcW * h) / srcH / 2) * 2
            val br = when {
                h >= 1080 -> 5000
                h >= 720 -> 2800
                h >= 480 -> 1400
                h >= 360 -> 800
                else -> 500
            }
            Rendition(name = "v$h", w = w, h = h, br = br, scaleFilter = "scale=$w:$h")
        }
    }

    private fun runFfmpeg(inputPath: String, outDir: Path, r: Rendition, mediaId: UUID) {
        val args = mutableListOf(ffmpegPath, "-y", "-i", inputPath)
        if (r.scaleFilter != null) args += listOf("-vf", r.scaleFilter)
        args += listOf(
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "23",
            "-maxrate", "${r.br}k",
            "-bufsize", "${2 * r.br}k",
            "-c:a", "aac",
            "-b:a", "128k",
            "-hls_time", "6",
            "-hls_playlist_type", "vod",
            "-hls_segment_filename", "${r.name}_%03d.ts",
            "${r.name}.m3u8",
        )
        // cwd = outDir so the generated playlist lists bare relative names the flat /stream route can serve.
        val proc = ProcessBuilder(args)
            .directory(outDir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            error("ffmpeg failed for media $mediaId rendition ${r.name} (exit ${proc.exitValue()}):\n${out.takeLast(2000)}")
        }
    }

    /** Master playlist, highest-bandwidth variant first (renditions are already sorted descending). */
    private fun masterPlaylist(renditions: List<Rendition>): String {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
        for (r in renditions) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=${r.br * 1000 + 128000}")
            if (r.w != null && r.h != null) sb.append(",RESOLUTION=${r.w}x${r.h}")
            sb.append('\n').append("${r.name}.m3u8").append('\n')
        }
        return sb.toString()
    }
}
