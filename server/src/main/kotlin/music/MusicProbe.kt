package wtf.jobin.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.math.roundToInt

data class MusicMeta(
    val durationSecs: Int?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val mimeType: String?,
)

class MusicProbe(private val binaryPath: String) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(file: Path): MusicMeta? = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(
            binaryPath, "-v", "error",
            "-show_entries", "format=duration:format_tags",
            "-of", "json", file.toAbsolutePath().toString(),
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) return@withContext null

        val format = json.parseToJsonElement(out).jsonObject["format"]?.jsonObject
            ?: return@withContext null
        val tags = format["tags"]?.jsonObject

        // Tag key casing/naming varies by container (FLAC Vorbis uses TITLE/ARTIST/ALBUM,
        // album_artist vs albumartist, track vs tracknumber). Fetch all tags, match case-insensitively.
        val lc: Map<String, String> = tags?.entries
            ?.mapNotNull { (k, v) -> v.jsonPrimitive.content.takeIf { it.isNotBlank() }?.let { k.lowercase() to it } }
            ?.toMap()
            ?: emptyMap()
        fun tag(vararg keys: String): String? = keys.firstNotNullOfOrNull { lc[it] }
        // track/disc tags arrive as "3" or "3/12" — keep the part before the slash.
        fun tagInt(vararg keys: String): Int? = tag(*keys)?.substringBefore('/')?.trim()?.toIntOrNull()

        MusicMeta(
            durationSecs = format["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()?.roundToInt(),
            title = tag("title"),
            artist = tag("artist"),
            album = tag("album"),
            albumArtist = tag("albumartist", "album_artist"),
            trackNumber = tagInt("track", "tracknumber"),
            discNumber = tagInt("disc", "discnumber"),
            mimeType = guessMime(file),
        )
    }

    private fun guessMime(file: Path): String? = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "aac", "m4b" -> "audio/mp4" // #96: m4b audiobooks are MP4 audio
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> null
    }
}
