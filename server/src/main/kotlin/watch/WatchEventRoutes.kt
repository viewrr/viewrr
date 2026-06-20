package wtf.jobin.watch

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

private val VALID_EVENT_TYPES = setOf("start", "progress", "pause", "stop", "finish")

// ponytail: hard cap at 50 matches the example query — bump when a real client asks.
private const val MAX_LIMIT = 50

@Serializable
data class WatchEventCreate(
    val mediaId: String,
    val positionSecs: Int,
    val eventType: String,
    val sessionId: String,
)

@Serializable
data class WatchEventCreated(val id: Long)

@Serializable
data class WatchEventView(
    val id: Long,
    val mediaId: String,
    val positionSecs: Int,
    val eventType: String,
    val sessionId: String,
    val createdAt: String,
)

fun Route.watchEventRoutes(repo: WatchEventRepository) {
    authenticate("auth-jwt") {
        post("/watch-events") {
            val req = call.receive<WatchEventCreate>()
            require(req.eventType in VALID_EVENT_TYPES) {
                "eventType must be one of $VALID_EVENT_TYPES"
            }
            require(req.positionSecs >= 0) { "positionSecs must be non-negative" }
            // UUID.fromString throws IllegalArgumentException → StatusPages → 400.
            val mediaId = UUID.fromString(req.mediaId)
            val sessionId = UUID.fromString(req.sessionId)
            val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val id = repo.insert(userId, mediaId, req.positionSecs, req.eventType, sessionId)
            call.respond(HttpStatusCode.Created, WatchEventCreated(id))
        }

        get("/watch-events/me") {
            val mediaId = UUID.fromString(
                call.request.queryParameters["mediaId"]
                    ?: throw IllegalArgumentException("mediaId is required"),
            )
            val requested = call.request.queryParameters["limit"]?.toIntOrNull() ?: MAX_LIMIT
            require(requested > 0) { "limit must be positive" }
            val limit = requested.coerceAtMost(MAX_LIMIT)
            val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val events = repo.findForUserAndMedia(userId, mediaId, limit)
            call.respond(events.map { it.toView() })
        }
    }
}

private fun WatchEventRow.toView() = WatchEventView(
    id = id,
    mediaId = mediaId.toString(),
    positionSecs = positionSecs,
    eventType = eventType,
    sessionId = sessionId.toString(),
    createdAt = createdAt.toString(),
)
