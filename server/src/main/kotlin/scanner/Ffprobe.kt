package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.math.roundToInt

data class MediaProbe(val durationSecs: Int?, val sizeBytes: Long?, val mimeType: String?)

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
        val format = root["format"]?.jsonObject ?: return@withContext null
        val duration = format["duration"]?.jsonPrimitive?.content?.toDoubleOrNull()?.roundToInt()
        val size = format["size"]?.jsonPrimitive?.content?.toLongOrNull()
        MediaProbe(duration, size, guessMime(file))
    }

    private fun guessMime(file: Path): String? = when (file.toString().substringAfterLast('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        else -> null
    }
}
