package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val json = Json { ignoreUnknownKeys = true }

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
            val dims = probeDimensions(inputPath)
            val renditions = buildRenditions(dims)
            val audioTracks = probeAudioTracks(inputPath)
            // ponytail: single audio group "aud"; per-track bitrate fixed at 128k.
            // Multi-audio only with a real video probe AND >=2 audio tracks; the dims
            // probe-failure fallback stays on the simple muxed single-audio path.
            val multiAudio = dims != null && audioTracks.size >= 2
            for (r in renditions) {
                runFfmpeg(inputPath, outDir, r, mediaId, includeAudio = !multiAudio)
            }
            if (multiAudio) {
                for (t in audioTracks) runAudioFfmpeg(inputPath, outDir, t, mediaId)
            }
            Files.writeString(playlist, masterPlaylist(renditions, if (multiAudio) audioTracks else emptyList()))
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

    /** An audio stream. [ordinal] is the 0-based position among audio streams (the K in ffmpeg `0:a:K`). */
    private data class AudioTrack(val ordinal: Int, val language: String?)

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

    /**
     * Audio streams in selection order. [AudioTrack.ordinal] is the 0-based index among audio
     * streams (ffmpeg's `0:a:K`); [AudioTrack.language] comes from the stream's `language` tag if
     * present. Empty list when the source has no audio or the probe fails / can't be parsed.
     */
    private fun probeAudioTracks(inputPath: String): List<AudioTrack> {
        return try {
            val proc = ProcessBuilder(
                ffprobePath, "-v", "error",
                "-select_streams", "a",
                "-show_entries", "stream=index:stream_tags=language",
                "-of", "json",
                inputPath,
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) return emptyList()
            val streams = json.parseToJsonElement(out).jsonObject["streams"]?.jsonArray
                ?: return emptyList()
            streams.mapIndexed { ordinal, el ->
                val lang = el.jsonObject["tags"]?.jsonObject?.get("language")?.jsonPrimitive?.content
                AudioTrack(ordinal, lang)
            }
        } catch (e: Exception) {
            emptyList()
        }
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

    /**
     * Encode one video rendition. When [includeAudio] the first audio stream is muxed in
     * (`-map 0:a:0?` + AAC) — the single-audio path. Otherwise the rendition is video-only (`-an`)
     * and audio ships as separate alternate-audio renditions via [runAudioFfmpeg].
     * Explicit `-map` keeps ffmpeg's default selection from dragging embedded subtitles into every
     * variant (stray *_vtt.m3u8); subtitles are handled separately by SubtitleExtractor.
     */
    private fun runFfmpeg(inputPath: String, outDir: Path, r: Rendition, mediaId: UUID, includeAudio: Boolean) {
        val args = mutableListOf(ffmpegPath, "-y", "-i", inputPath)
        args += if (includeAudio) listOf("-map", "0:v:0", "-map", "0:a:0?") else listOf("-map", "0:v:0", "-an")
        if (r.scaleFilter != null) args += listOf("-vf", r.scaleFilter)
        args += listOf(
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "23",
            "-maxrate", "${r.br}k",
            "-bufsize", "${2 * r.br}k",
        )
        if (includeAudio) args += listOf("-c:a", "aac", "-b:a", "128k")
        args += listOf(
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

    /**
     * Encode one alternate-audio rendition (audio-only, `-vn`) into a$K.m3u8 + a${K}_%03d.ts.
     * Drains merged stdout/stderr and throws on non-zero exit, like [runFfmpeg].
     */
    private fun runAudioFfmpeg(inputPath: String, outDir: Path, track: AudioTrack, mediaId: UUID) {
        val k = track.ordinal
        val args = listOf(
            ffmpegPath, "-y", "-i", inputPath,
            "-map", "0:a:$k", "-vn",
            "-c:a", "aac", "-b:a", "128k",
            "-hls_time", "6",
            "-hls_playlist_type", "vod",
            "-hls_segment_filename", "a${k}_%03d.ts",
            "a$k.m3u8",
        )
        val proc = ProcessBuilder(args)
            .directory(outDir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) {
            error("ffmpeg failed for media $mediaId audio track $k (exit ${proc.exitValue()}):\n${out.takeLast(2000)}")
        }
    }

    /**
     * Master playlist, highest-bandwidth variant first (renditions are already sorted descending).
     * With >=2 audio tracks, emits an EXT-X-MEDIA alternate-audio entry per track (group "aud",
     * first track DEFAULT=YES) and tags every variant with AUDIO="aud". With <=1 track the audio is
     * muxed into the variants and no media/group lines are written (byte-identical to single-audio).
     */
    private fun masterPlaylist(renditions: List<Rendition>, audioTracks: List<AudioTrack>): String {
        val multiAudio = audioTracks.size >= 2
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
        if (multiAudio) {
            for (t in audioTracks) {
                val k = t.ordinal
                val name = t.language?.uppercase() ?: "Track ${k + 1}"
                val lang = t.language ?: "und"
                val default = if (k == 0) "YES" else "NO"
                sb.append(
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"$name\"," +
                        "LANGUAGE=\"$lang\",DEFAULT=$default,AUTOSELECT=YES,URI=\"a$k.m3u8\"\n",
                )
            }
        }
        for (r in renditions) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=${r.br * 1000 + 128000}")
            if (r.w != null && r.h != null) sb.append(",RESOLUTION=${r.w}x${r.h}")
            if (multiAudio) sb.append(",AUDIO=\"aud\"")
            sb.append('\n').append("${r.name}.m3u8").append('\n')
        }
        return sb.toString()
    }
}
