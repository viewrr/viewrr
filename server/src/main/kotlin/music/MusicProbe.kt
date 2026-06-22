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
            "-show_entries", "format=duration:format_tags=title,artist,album,album_artist,track,disc",
            "-of", "json", file.toAbsolutePath().toString(),
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) return@withContext null

        val format = json.parseToJsonElement(out).jsonObject["format"]?.jsonObject
            ?: return@withContext null
        val tags = format["tags"]?.jsonObject

        fun tag(key: String): String? =
            tags?.get(key)?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        // track/disc tags arrive as "3" or "3/12" — keep the part before the slash.
        fun tagInt(key: String): Int? = tag(key)?.substringBefore('/')?.trim()?.toIntOrNull()

        MusicMeta(
            durationSecs = format["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()?.roundToInt(),
            title = tag("title"),
            artist = tag("artist"),
            album = tag("album"),
            albumArtist = tag("album_artist"),
            trackNumber = tagInt("track"),
            discNumber = tagInt("disc"),
            mimeType = guessMime(file),
        )
    }

    private fun guessMime(file: Path): String? = when (file.extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> null
    }
}
