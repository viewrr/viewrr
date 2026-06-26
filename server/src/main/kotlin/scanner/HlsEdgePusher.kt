package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import wtf.jobin.cluster.nodeOnline
import wtf.jobin.db.LOCAL_NODE_ID
import wtf.jobin.db.MediaCopies
import wtf.jobin.db.Nodes
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * #95 (Phase 18): edge-cache the Hub-produced HLS bundle to the owning same-LAN Node.
 *
 * After the Hub transcodes a title whose chosen Copy ([wtf.jobin.db.resolveCopy]) lives on a
 * REMOTE online node with a client-plane address, this pushes the produced `{profileKey}/hls`
 * directory to that node's gated `PUT /hls/...` receiver ([wtf.jobin.cluster.agentHlsRoutes]) so
 * later same-LAN plays come over the LAN instead of round-tripping through the Hub.
 *
 * DEFAULT-OFF: the caller ([TranscodeCoordinator]) only invokes [pushIfEligible] when
 * `media.edgeCacheEnabled` is true. With it false (the default) this class is never reached, so the
 * Hub-serves-HLS path is byte-identical to today.
 *
 * On a fully successful push, the node's HLS playlist URL is recorded in `media_copies.hls_path`
 * for that (titleId, nodeId) Copy. That column is what [wtf.jobin.media.localityUrl] consults to
 * decide whether the node actually has the bundle — so a partial/failed push never makes the Hub
 * hand out a Node URL that 404s.
 *
 * ponytail: per-file PUT over stdlib HttpClient (the same no-new-dep approach as AgentBootstrap /
 * AgentScanner). Auth is the shared enrollment secret in the query string, mirroring /raw + #74.
 * Best-effort and fire-and-forget at the call site: any failure leaves the Hub path intact.
 *
 * TODO needs node deployed: real push/serve verification needs a second Node reachable over the
 * mesh/LAN (Headscale). Until then this is covered only by compilation + the default-off guard.
 */
class HlsEdgePusher(
    private val db: R2dbcDatabase,
    private val hlsRoot: Path,
    private val enrollmentSecret: String,
) {
    private val log = LoggerFactory.getLogger("wtf.jobin.scanner.HlsEdgePusher")
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /**
     * #95: push the just-built bundle at [playlist] for [mediaId] to the owning node, if eligible.
     * No-op (returns false) when the chosen copy is local, the node is offline / has no client
     * address, or the on-disk bundle is missing. Records `media_copies.hls_path` on full success.
     */
    suspend fun pushIfEligible(mediaId: UUID, playlist: Path): Boolean {
        // The chosen online copy. Local copies have no separate client plane -> nothing to push to.
        val copy = wtf.jobin.db.resolveCopy(db, mediaId) ?: return false
        if (copy.nodeId == LOCAL_NODE_ID) return false

        val node = suspendTransaction(db) {
            Nodes.select(Nodes.clientAddress, Nodes.lastSeenAt)
                .where { Nodes.id eq copy.nodeId }
                .map { it[Nodes.clientAddress] to it[Nodes.lastSeenAt] }
                .firstOrNull()
        } ?: return false
        val (clientAddress, lastSeenAt) = node
        if (clientAddress.isNullOrBlank()) return false
        // #83: only push to a node we believe is online (null last_seen = never seen = offline).
        if (!nodeOnline(lastSeenAt)) return false

        // Bundle dir = {profileKey}/hls (the playlist's parent). Derive the {lib}/{media}/{profileKey}
        // path segments by relativizing the hls dir against hlsRoot.
        val hlsDir = playlist.toAbsolutePath().normalize().parent ?: return false
        if (!Files.isDirectory(hlsDir)) return false
        val rel = runCatching { hlsRoot.toAbsolutePath().normalize().relativize(hlsDir) }.getOrNull() ?: return false
        // Expect exactly {lib}/{media}/{profileKey}/hls
        if (rel.nameCount != 4 || rel.getName(3).toString() != "hls") {
            log.warn("#95 edge push: unexpected hls dir layout {} (rel={})", hlsDir, rel)
            return false
        }
        val lib = rel.getName(0).toString()
        val media = rel.getName(1).toString()
        val profileKey = rel.getName(2).toString()

        val base = clientAddress.trimEnd('/')
        val tok = URLEncoder.encode(enrollmentSecret, "UTF-8")

        // Upload every file in the bundle (playlist + variant playlists + .ts segments). Any single
        // failure aborts (we don't record hls_path), so localityUrl keeps preferring the Hub.
        val files = withContext(Dispatchers.IO) {
            Files.walk(hlsDir).use { w -> w.filter { Files.isRegularFile(it) }.toList() }
        }
        if (files.isEmpty()) return false
        for (f in files) {
            val name = f.fileName.toString()
            val url = "http://$base/hls/$lib/$media/$profileKey/$name?token=$tok"
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/octet-stream")
                        .PUT(HttpRequest.BodyPublishers.ofFile(f))
                        .build()
                    val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
                    resp.statusCode() in 200..299
                }.getOrElse { false }
            }
            if (!ok) {
                log.warn("#95 edge push: upload failed for {} -> {}; leaving Hub path active", name, base)
                return false
            }
        }

        // Full success: record the node's playlist URL so localityUrl can prefer it. Same shape the
        // node serves (GET /hls/{lib}/{media}/{profileKey}/hls/playlist.m3u8). Stored WITH the token
        // so the player can fetch directly (v0 LAN auth, same model as the /raw direct URL in #79).
        val nodeHlsUrl = "http://$base/hls/$lib/$media/$profileKey/hls/playlist.m3u8?token=$tok"
        suspendTransaction(db) {
            MediaCopies.update({ (MediaCopies.titleId eq mediaId) and (MediaCopies.nodeId eq copy.nodeId) }) {
                it[hlsPath] = nodeHlsUrl
                it[updatedAt] = Instant.now()
            }
        }
        log.info("#95 edge push: cached {} files to node {} for media {}", files.size, copy.nodeId, mediaId)
        return true
    }
}
