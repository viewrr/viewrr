package wtf.jobin.cluster

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import wtf.jobin.scanner.Ffprobe
import wtf.jobin.scanner.FilenameParser
import wtf.jobin.scanner.MEDIA_EXTS
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Phase 15 (#81): agent-side scan. Walk the configured library roots, ffprobe each video,
 * filename-parse it, and POST the records to the Hub (authed by the per-node token). The agent
 * holds no DB — the Hub persists against this agent's node. ponytail: one-shot scan after
 * register; a live FS watcher / periodic rescan is a follow-up.
 */
class AgentScanner(
    private val ffprobe: Ffprobe,
    private val hubBaseUrl: String,
    private val tokenFile: String,
    private val libraryRoots: List<String>,
) {
    private val log = LoggerFactory.getLogger("wtf.jobin.cluster.AgentScanner")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun scanAndPush(): IngestResult? {
        val tokenPath = Path.of(tokenFile)
        if (!Files.exists(tokenPath)) {
            log.warn("Agent scan: no token file ($tokenFile) — register first")
            return null
        }
        val reg = json.decodeFromString<RegisterResponse>(Files.readString(tokenPath))

        val records = mutableListOf<AgentMediaRecord>()
        for (root in libraryRoots) {
            val rootPath = Path.of(root)
            if (!Files.isDirectory(rootPath)) continue
            val files = withContext(Dispatchers.IO) {
                Files.walk(rootPath).use { s ->
                    s.filter { Files.isRegularFile(it) && it.extension.lowercase() in MEDIA_EXTS }.toList()
                }
            }
            for (f in files) {
                val probe = ffprobe.probe(f) ?: continue
                if (!probe.hasVideo) continue
                val parsed = FilenameParser.parse(f.nameWithoutExtension)
                records += AgentMediaRecord(
                    originalPath = f.toAbsolutePath().toString(),
                    root = root,
                    title = f.nameWithoutExtension,
                    cleanTitle = parsed.cleanTitle,
                    showTitle = parsed.showTitle,
                    seasonNumber = parsed.seasonNumber,
                    episodeNumber = parsed.episodeNumber,
                    year = parsed.year,
                    durationSecs = probe.durationSecs,
                    sizeBytes = probe.sizeBytes,
                    mimeType = probe.mimeType,
                )
            }
        }
        if (records.isEmpty()) {
            log.info("Agent scan: no playable media under $libraryRoots")
            return IngestResult(0, 0, 0)
        }

        val req = HttpRequest.newBuilder(URI.create("${hubBaseUrl.trimEnd('/')}/agent/media"))
            .header("Content-Type", "application/json")
            .header("X-Viewrr-Token", reg.token)
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(AgentMediaPush(records))))
            .build()
        val resp = try {
            withContext(Dispatchers.IO) { HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()) }
        } catch (e: Exception) {
            log.error("Agent scan: push to $hubBaseUrl failed: ${e.message}")
            return null
        }
        if (resp.statusCode() != 200) {
            log.error("Agent scan: hub rejected push (HTTP ${resp.statusCode()})")
            return null
        }
        val result = json.decodeFromString<IngestResult>(resp.body())
        log.info("Agent scan: pushed ${records.size} records -> added=${result.added} updated=${result.updated} libs=${result.libraries}")
        return result
    }
}
