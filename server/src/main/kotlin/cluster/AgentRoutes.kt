package wtf.jobin.cluster

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Libraries
import wtf.jobin.db.MediaItems
import wtf.jobin.db.Nodes
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

@Serializable
data class RegisterRequest(
    val enrollmentSecret: String,
    val name: String,
    val meshAddress: String? = null,
    val clientAddress: String? = null,
)

@Serializable
data class RegisterResponse(val nodeId: String, val token: String)

// Phase 15 (#81): one scanned video the agent reports. The agent owns no DB, so it sends
// already-probed + filename-parsed records; the Hub persists them against the agent's node.
@Serializable
data class AgentMediaRecord(
    val originalPath: String,
    val root: String,
    val title: String,
    val cleanTitle: String? = null,
    val showTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val year: Int? = null,
    val durationSecs: Int? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
)

@Serializable
data class AgentMediaPush(val records: List<AgentMediaRecord>)

@Serializable
data class IngestResult(val added: Int, val updated: Int, val libraries: Int)

fun Route.agentRoutes(registry: NodeRegistry, db: R2dbcDatabase) {
    // #69: enrollment-secret -> nodeId + token.
    post("/agent/register") {
        val req = call.receive<RegisterRequest>()
        // #79: the node's IP as the Hub sees it on the LAN — the locality (same-LAN) heuristic.
        val egressIp = call.request.origin.remoteHost
        val r = try {
            registry.register(req.enrollmentSecret, req.name, req.meshAddress, req.clientAddress, egressIp)
        } catch (_: NodeRegistry.BadEnrollment) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        call.respond(RegisterResponse(r.nodeId.toString(), r.token))
    }

    // #83: agent liveness. Token -> nodeId -> stamp last_seen_at. Online status is derived
    // from this (nodeOnline()), used by locality (#79) and disabled-catalog (#84).
    post("/agent/heartbeat") {
        val token = call.request.headers["X-Viewrr-Token"]
        val nodeId = token?.let { registry.resolve(it) } ?: return@post call.respond(HttpStatusCode.Unauthorized)
        suspendTransaction(db) {
            Nodes.update({ Nodes.id eq nodeId }) { it[lastSeenAt] = Instant.now() }
        }
        call.respond(HttpStatusCode.NoContent)
    }

    // #81: agent pushes its scanned media. Auth = the per-node token (resolved to nodeId).
    // Lazy transcode (#75) means we persist metadata only; no hls until first request.
    post("/agent/media") {
        val token = call.request.headers["X-Viewrr-Token"]
        val nodeId = token?.let { registry.resolve(it) } ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val push = call.receive<AgentMediaPush>()

        var newLibs = 0
        val libIds = HashMap<String, UUID>()
        for (root in push.records.map { it.root }.distinct()) {
            val existing = suspendTransaction(db) {
                Libraries.select(Libraries.id)
                    .where { (Libraries.nodeId eq nodeId) and (Libraries.rootPath eq root) }
                    .map { it[Libraries.id].value }.firstOrNull()
            }
            libIds[root] = existing ?: suspendTransaction(db) {
                Libraries.insertAndGetId {
                    it[Libraries.nodeId] = nodeId
                    it[Libraries.name] = Path.of(root).fileName?.toString() ?: root
                    it[Libraries.kind] = "movies"
                    it[Libraries.rootPath] = root
                    it[Libraries.watchEnabled] = false // agent owns the watch; hub just stores
                    it[Libraries.createdAt] = Instant.now()
                }.value
            }.also { newLibs++ }
        }

        var added = 0
        var updated = 0
        for (rec in push.records) {
            val libId = libIds.getValue(rec.root)
            val now = Instant.now()
            val existing = suspendTransaction(db) {
                MediaItems.select(MediaItems.id)
                    .where { MediaItems.originalPath eq rec.originalPath }
                    .map { it[MediaItems.id].value }.firstOrNull()
            }
            if (existing != null) {
                suspendTransaction(db) {
                    MediaItems.update({ MediaItems.id eq existing }) {
                        it[MediaItems.nodeId] = nodeId
                        it[MediaItems.libraryId] = libId
                        it[MediaItems.durationSecs] = rec.durationSecs
                        it[MediaItems.sizeBytes] = rec.sizeBytes
                        it[MediaItems.updatedAt] = now
                    }
                }
                updated++
            } else {
                suspendTransaction(db) {
                    MediaItems.insertAndGetId {
                        it[MediaItems.nodeId] = nodeId
                        it[MediaItems.libraryId] = libId
                        it[MediaItems.title] = rec.title
                        it[MediaItems.cleanTitle] = rec.cleanTitle
                        it[MediaItems.showTitle] = rec.showTitle
                        it[MediaItems.seasonNumber] = rec.seasonNumber
                        it[MediaItems.episodeNumber] = rec.episodeNumber
                        it[MediaItems.year] = rec.year?.toShort()
                        it[MediaItems.originalPath] = rec.originalPath
                        it[MediaItems.durationSecs] = rec.durationSecs
                        it[MediaItems.sizeBytes] = rec.sizeBytes
                        it[MediaItems.mimeType] = rec.mimeType
                        it[MediaItems.createdAt] = now
                        it[MediaItems.updatedAt] = now
                    }
                }
                added++
            }
        }
        call.respond(IngestResult(added, updated, newLibs))
    }
}
