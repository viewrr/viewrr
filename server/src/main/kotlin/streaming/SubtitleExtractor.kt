package wtf.jobin.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class SubtitleTrack(val index: Int, val lang: String?, val label: String?, val url: String)

class SubtitleExtractor(
    private val ffmpegPath: String,
    private val ffprobePath: String,
    private val hlsRoot: String,
    private val db: R2dbcDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Lists subtitle tracks for a media item, extracting embedded (text) subtitle streams and
     * matching sidecar files to flat WebVTT files under the media's hls dir. Results are cached
     * to `subs.json` so we probe/convert at most once per media item.
     */
    suspend fun tracks(mediaId: UUID): List<SubtitleTrack> {
        val (libraryId, inputPath) = suspendTransaction(db) {
            MediaItems.select(MediaItems.libraryId, MediaItems.originalPath)
                .where { MediaItems.id eq mediaId }
                .map { it[MediaItems.libraryId].value to it[MediaItems.originalPath] }
                .firstOrNull()
        } ?: return emptyList()

        val outDir = Path.of(hlsRoot, libraryId.toString(), mediaId.toString(), "hls")
        Files.createDirectories(outDir)
        val manifest = outDir.resolve("subs.json")

        if (Files.exists(manifest)) {
            return json.decodeFromString<List<SubtitleTrack>>(Files.readString(manifest))
        }

        val tracks = withContext(Dispatchers.IO) {
            val embedded = extractEmbedded(mediaId, inputPath, outDir)
            embedded + extractSidecars(mediaId, inputPath, outDir, embedded.size)
        }

        // Always write — even an empty list — so we never re-probe an item with no subtitles.
        Files.writeString(manifest, json.encodeToString(tracks))
        return tracks
    }

    /** Enumerate embedded subtitle streams and transcode each text track to `sub_K.vtt`. */
    private fun extractEmbedded(mediaId: UUID, inputPath: String, outDir: Path): List<SubtitleTrack> {
        val probe = ProcessBuilder(
            ffprobePath, "-v", "error",
            "-select_streams", "s",
            "-show_entries", "stream=index:stream_tags=language,title",
            "-of", "json", inputPath,
        ).redirectErrorStream(true).start()
        val out = probe.inputStream.bufferedReader().readText()
        if (probe.waitFor() != 0) {
            error("ffprobe failed for media $mediaId (exit ${probe.exitValue()}):\n${out.takeLast(2000)}")
        }

        val streams = json.parseToJsonElement(out).jsonObject["streams"]?.jsonArray ?: return emptyList()
        val tracks = mutableListOf<SubtitleTrack>()
        streams.forEachIndexed { k, stream ->
            val tags = stream.jsonObject["tags"]?.jsonObject
            val lang = tags?.get("language")?.jsonPrimitive?.contentOrNull
            val title = tags?.get("title")?.jsonPrimitive?.contentOrNull

            val conv = ProcessBuilder(
                ffmpegPath, "-y", "-i", inputPath,
                "-map", "0:s:$k", "-c:s", "webvtt", "sub_$k.vtt",
            ).directory(outDir.toFile()).redirectErrorStream(true).start()
            conv.inputStream.bufferedReader().readText()
            // ponytail: text subtitles only; image-based tracks (PGS/dvdsub) can't become webvtt — skipped.
            if (conv.waitFor() != 0) return@forEachIndexed

            tracks += SubtitleTrack(
                index = k,
                lang = lang,
                label = title ?: lang ?: "Track ${k + 1}",
                url = "/stream/$mediaId/sub_$k.vtt",
            )
        }
        return tracks
    }

    /** Match sidecar `.srt`/`.vtt` files next to the source and expose them as flat `sub_ext_n.vtt`. */
    private fun extractSidecars(mediaId: UUID, inputPath: String, outDir: Path, indexOffset: Int): List<SubtitleTrack> {
        val source = Path.of(inputPath)
        val parent = source.parent ?: return emptyList()
        if (!Files.isDirectory(parent)) return emptyList()
        val baseName = source.fileName.toString().substringBeforeLast('.')

        // ponytail: basic sidecar match only — name starts with source basename, ends in .srt/.vtt.
        val sidecars = Files.list(parent).use { stream ->
            stream.filter {
                val name = it.fileName.toString()
                val lower = name.lowercase()
                Files.isRegularFile(it) && name.startsWith(baseName) &&
                    (lower.endsWith(".srt") || lower.endsWith(".vtt"))
            }.sorted().toList()
        }

        val tracks = mutableListOf<SubtitleTrack>()
        sidecars.forEachIndexed { n, sidecar ->
            val name = sidecar.fileName.toString()
            val lower = name.lowercase()
            val lang = name.removePrefix(baseName)
                .removeSuffix(name.takeLast(4)).trim('.')
                .ifBlank { null }
            val target = "sub_ext_$n.vtt"

            if (lower.endsWith(".vtt")) {
                Files.copy(sidecar, outDir.resolve(target), StandardCopyOption.REPLACE_EXISTING)
            } else {
                val conv = ProcessBuilder(
                    ffmpegPath, "-y", "-i", sidecar.toAbsolutePath().toString(), target,
                ).directory(outDir.toFile()).redirectErrorStream(true).start()
                conv.inputStream.bufferedReader().readText()
                if (conv.waitFor() != 0) return@forEachIndexed
            }

            tracks += SubtitleTrack(
                index = indexOffset + n,
                lang = lang,
                label = lang ?: "External ${n + 1}",
                url = "/stream/$mediaId/sub_ext_$n.vtt",
            )
        }
        return tracks
    }
}
