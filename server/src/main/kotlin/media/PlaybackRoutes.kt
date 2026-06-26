package wtf.jobin.media

import io.ktor.http.* // #84: HttpStatusCode.ServiceUnavailable for the unavailable-title path
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.origin
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.LOCAL_NODE_ID
import wtf.jobin.db.MediaItems
import wtf.jobin.db.Nodes
import wtf.jobin.db.WatchEvents
import wtf.jobin.rating.isVisible
import wtf.jobin.rating.maxRatingFor
import wtf.jobin.stremio.StremioKeys
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Serializable
data class PlaybackResolve(
    val url: String,
    val type: String = "hls",
    val startPositionSecs: Int = 0,
    val subtitlesUrl: String,
    val trickplayUrl: String,
    // #79: true when [url] points straight at a same-LAN Node's /raw (locality serving) rather
    // than the Hub HLS playlist. Default false keeps the existing Hub contract unchanged.
    val direct: Boolean = false,
)

/**
 * #111 — the single playback call a first-party client makes. Resolves a title to a
 * playable HLS URL (path-keyed by the caller's per-device stremio-key, so the player
 * itself needs no bearer) plus resume position and sidecar URLs.
 *
 * ponytail: defaults to the Hub HLS URL. #79 adds locality serving: when the client shares a
 * LAN with the owning Node, [url] points straight at that Node's /raw instead (opt-in, never a
 * regression — any miss falls back to the Hub path exactly as before).
 */
fun Route.playbackRoutes(
    db: R2dbcDatabase,
    stremioKeys: StremioKeys,
    publicBaseUrl: String,
    enrollmentSecret: String, // #79: reused as the Node /raw token (same v0 LAN auth as HlsTranscoder)
    edgeCacheEnabled: Boolean = false, // #95: DEFAULT-OFF; when true prefer a node-cached HLS URL
) {
    authenticate("auth-jwt") {
        get("/playback/{id}") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val id = UUID.fromString(call.parameters["id"]!!)

            // Pull rating + owning node together; pair with Unit so "no row" stays distinct
            // from "row with null rating".
            val found = suspendTransaction(db) {
                MediaItems.select(MediaItems.contentRating, MediaItems.nodeId, MediaItems.originalPath)
                    .where { MediaItems.id eq id }
                    .map { Triple(it[MediaItems.contentRating], it[MediaItems.nodeId].value, it[MediaItems.originalPath]) }
                    .firstOrNull()
            } ?: throw NotFoundException()
            // 404 (not 403) on parental-hidden too — don't leak existence.
            if (!isVisible(maxRatingFor(db, uid), found.first)) throw NotFoundException()
            val nodeId = found.second
            val originalPath = found.third

            // #84: a Title with no online copy is visible but not currently playable. Return an
            // explicit "unavailable" (503) rather than a Hub URL that would later 500/404 when the
            // transcode source can't be reached — never crash, never hide. #86: fire the idempotent
            // re-acquire trigger so the missing media can be re-fetched later (Phase 17).
            // ponytail: hasOnlineCopy treats LOCAL_NODE_ID as always-online, so single-box installs
            // never hit this path — today's local playback is byte-for-byte unchanged.
            if (!wtf.jobin.db.hasOnlineCopy(db, id)) {
                wtf.jobin.media.ReacquireService.trigger(id)
                return@get call.respond(HttpStatusCode.ServiceUnavailable, "title currently unavailable (offline)")
            }

            val key = stremioKeys.keyFor(uid)
            val base = publicBaseUrl.trimEnd('/')
            val start = suspendTransaction(db) {
                WatchEvents.select(WatchEvents.positionSecs)
                    .where { (WatchEvents.userId eq uid) and (WatchEvents.mediaId eq id) }
                    .orderBy(WatchEvents.createdAt to SortOrder.DESC)
                    .limit(1)
                    .map { it[WatchEvents.positionSecs] }
                    .firstOrNull() ?: 0
            }

            // #79: locality serving. If the playback client's egress IP matches the owning Node's
            // egress IP (same-LAN heuristic) and the Node is online-ish with a client_address, hand
            // back the Node's /raw URL — same shape HlsTranscoder.resolveSource builds — so the bytes
            // never round-trip through the Hub. Any miss leaves the Hub HLS url untouched (the default).
            val clientEgressIp = call.request.origin.remoteHost
            // #95 (Phase 18): edge-cache preference. ONLY when enabled (default false) AND the owning
            // same-LAN node actually holds the pushed HLS bundle (recorded in media_copies.hls_path by
            // HlsEdgePusher). Falls through to the #79 direct-/raw path and finally the Hub HLS, so a
            // miss is never a regression. When edgeCacheEnabled is false this is skipped entirely.
            val edgeUrl = if (edgeCacheEnabled) {
                edgeCacheUrl(db, id, nodeId, clientEgressIp)
            } else {
                null
            }
            val localUrl = edgeUrl ?: localityUrl(db, nodeId, originalPath, clientEgressIp, enrollmentSecret)

            call.respond(
                if (localUrl != null) {
                    PlaybackResolve(
                        url = localUrl,
                        startPositionSecs = start,
                        subtitlesUrl = "$base/media/$id/subtitles",
                        trickplayUrl = "$base/media/$id/trickplay",
                        direct = true,
                    )
                } else {
                    PlaybackResolve(
                        url = "$base/stream/k/$key/$id/playlist.m3u8",
                        startPositionSecs = start,
                        subtitlesUrl = "$base/media/$id/subtitles",
                        trickplayUrl = "$base/media/$id/trickplay",
                    )
                },
            )
        }
    }
}

// #79: nodes are considered online-ish if they heartbeated within this window. Heartbeat (#83)
// isn't built yet, so last_seen_at is currently always null — see localityUrl for the null handling.
private val ONLINE_WINDOW: Duration = Duration.ofMinutes(5)

/**
 * #79 — return a Node /raw URL when [nodeId]'s media should be served locally to a client whose
 * egress IP is [clientEgressIp], else null (caller keeps the Hub HLS url). Conditions: not the
 * local node, node has an egress_ip equal to the client's, node has a client_address, and the node
 * is online-ish.
 *
 * ponytail: egress-IP equality is the LAN heuristic — behind CGNAT two unrelated clients can share
 * a public egress IP and false-match. Acceptable for v0 (worst case: a same-public-IP-but-not-same-LAN
 * client gets a Node URL it can't reach; the player can retry the Hub). Tighten with an explicit
 * LAN-membership signal later.
 * ponytail: "direct-play" is simplified to "same-LAN" here — if the client is on the Node's LAN we
 * serve the raw file and let the player handle the container. Real capability-profile matching is #84.
 * ponytail: last_seen_at is null until heartbeat (#83) ships, so a null last_seen is treated as
 * online-ish (don't disable locality before the heartbeat exists); once #83 lands, null = offline.
 */
private suspend fun localityUrl(
    db: R2dbcDatabase,
    nodeId: UUID,
    originalPath: String,
    clientEgressIp: String,
    enrollmentSecret: String,
): String? {
    if (nodeId == LOCAL_NODE_ID) return null // local node has no separate client plane
    val node = suspendTransaction(db) {
        Nodes.select(Nodes.egressIp, Nodes.clientAddress, Nodes.lastSeenAt)
            .where { Nodes.id eq nodeId }
            .map { Triple(it[Nodes.egressIp], it[Nodes.clientAddress], it[Nodes.lastSeenAt]) }
            .firstOrNull()
    } ?: return null
    val (egressIp, clientAddress, lastSeenAt) = node
    if (egressIp.isNullOrBlank() || egressIp != clientEgressIp) return null // not same-LAN
    if (clientAddress.isNullOrBlank()) return null // no client-plane address to point at
    // #83: real online check — heartbeat stamps last_seen; null = never-seen = offline.
    if (!wtf.jobin.cluster.nodeOnline(lastSeenAt)) return null
    // Same URL shape as HlsTranscoder.resolveSource: token + path in query, path LAST.
    val tok = URLEncoder.encode(enrollmentSecret, "UTF-8")
    val p = URLEncoder.encode(originalPath, "UTF-8")
    return "http://$clientAddress/raw?token=$tok&path=$p"
}

/**
 * #95 (Phase 18) — return the owning Node's cached-HLS URL when edge-cache applies, else null.
 *
 * Preconditions mirror [localityUrl]'s same-LAN gate (remote node, egress-IP match, online,
 * client-plane address present) PLUS the node must actually hold the bundle: the chosen Copy's
 * `media_copies.hls_path` must be the node HLS URL [HlsEdgePusher] recorded on a successful push.
 * That stored value already embeds the node base + token, so it's returned verbatim.
 *
 * Caller invokes this ONLY when media.edgeCacheEnabled is true, so with edge-cache off the entire
 * code path is dead and playback resolution is byte-identical to today.
 *
 * ponytail: keyed on [titleId] + [nodeId] (the MediaItems.node_id used by #79). For the single-copy
 * / V13-backfilled case that copy's node == MediaItems.node_id, so the lookup matches. Multi-copy
 * selection (resolveCopy) is the follow-up once the node binary is deployed.
 * TODO needs node deployed: hls_path is only ever populated by a real push to a running Node, so
 * until then this returns null and the Hub HLS path serves — exactly as before this issue.
 */
private suspend fun edgeCacheUrl(
    db: R2dbcDatabase,
    titleId: UUID,
    nodeId: UUID,
    clientEgressIp: String,
): String? {
    if (nodeId == LOCAL_NODE_ID) return null // local node serves Hub HLS directly
    val node = suspendTransaction(db) {
        Nodes.select(Nodes.egressIp, Nodes.clientAddress, Nodes.lastSeenAt)
            .where { Nodes.id eq nodeId }
            .map { Triple(it[Nodes.egressIp], it[Nodes.clientAddress], it[Nodes.lastSeenAt]) }
            .firstOrNull()
    } ?: return null
    val (egressIp, clientAddress, lastSeenAt) = node
    if (egressIp.isNullOrBlank() || egressIp != clientEgressIp) return null // not same-LAN
    if (clientAddress.isNullOrBlank()) return null
    if (!wtf.jobin.cluster.nodeOnline(lastSeenAt)) return null
    // The pushed-bundle marker: media_copies.hls_path for this Title's copy on this node. Non-null
    // (and a node URL) only after HlsEdgePusher confirmed a full push. Returned as-is (token baked in).
    val hls = suspendTransaction(db) {
        wtf.jobin.db.MediaCopies
            .select(wtf.jobin.db.MediaCopies.hlsPath)
            .where { (wtf.jobin.db.MediaCopies.titleId eq titleId) and (wtf.jobin.db.MediaCopies.nodeId eq nodeId) }
            .map { it[wtf.jobin.db.MediaCopies.hlsPath] }
            .firstOrNull()
    }
    return hls?.takeIf { it.isNotBlank() }
}
