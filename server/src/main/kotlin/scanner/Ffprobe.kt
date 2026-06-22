package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

data class MediaProbe(val durationSecs: Int?, val sizeBytes: Long?, val mimeType: String?, val hasVideo: Boolean)

class Ffprobe(private val binaryPath: String) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(file: Path): MediaProbe? = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(
            binaryPath, "-v", "error", "-show_format", "-show_streams",
            "-of", "json", file.toAbsolutePath().toString(),
        ).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        if (proc.waitFor() != 0) return@withContext null

        val root = json.parseToJsonElement(out).jsonObject
        val format = root["format"]?.jsonObject
            ?: return@withContext null
        val duration = format["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()?.roundToInt()
        val size = format["size"]?.jsonPrimitive?.content?.toLongOrNull()
        val hasVideo = root["streams"]?.jsonArray?.any {
            it.jsonObject["codec_type"]?.jsonPrimitive?.content == "video"
        } ?: false
        MediaProbe(duration, size, Files.probeContentType(file), hasVideo)
    }
}
