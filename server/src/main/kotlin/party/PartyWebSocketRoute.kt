package wtf.jobin.party

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.UserSession
import wtf.jobin.db.PartyMembers
import java.util.UUID

@Serializable
private data class WsTicketResponse(val cookieName: String, val cookieValue: String)

fun Route.partyWebSocketRoutes(hub: PartyHub, db: R2dbcDatabase) {
    authenticate("auth-jwt") {
        post("/party-rooms/{id}/ws-ticket") {
            val userId = call.userIdFromJwt()
            val roomId = UUID.fromString(call.parameters["id"]!!)
            if (!isActiveMember(db, roomId, userId)) {
                call.respond(HttpStatusCode.Forbidden)
                return@post
            }
            // Sessions plugin writes Set-Cookie on response; browsers attach automatically
            // on the WS upgrade. cookieValue stays empty — native clients can parse
            // the Set-Cookie header. ponytail: skip read-back, plugin writes async.
            call.sessions.set(UserSession(userId.toString(), isAdmin = false))
            call.respond(WsTicketResponse(cookieName = "VIEWRR_SESSION", cookieValue = ""))
        }
    }

    authenticate("auth-session") {
        webSocket("/party-rooms/{id}/ws") {
            val session = call.sessions.get<UserSession>()
                ?: call.principal<UserSession>()
            if (session == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no session"))
                return@webSocket
            }
            val userId = UUID.fromString(session.userId)
            val roomId = runCatching { UUID.fromString(call.parameters["id"]!!) }.getOrNull()
            if (roomId == null) {
                close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "bad room id"))
                return@webSocket
            }
            if (!isActiveMember(db, roomId, userId)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "not a member"))
                return@webSocket
            }

            val inbox = hub.subscribe(roomId)
            val pump = launch {
                try {
                    for (msg in inbox) send(Frame.Text(msg))
                } catch (_: ClosedSendChannelException) {
                    // ws closed — drain ends here.
                }
            }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val text = frame.readText()
                    val msg = runCatching { PartyJson.decodeFromString<PartyMessage>(text) }.getOrNull()
                        ?: continue
                    when (msg) {
                        is PartyMessage.PlayEvent -> {
                            hub.updateState(roomId, msg.positionSecs, isPlaying = true)
                            hub.publish(roomId, text)
                        }
                        is PartyMessage.PauseEvent -> {
                            hub.updateState(roomId, msg.positionSecs, isPlaying = false)
                            hub.publish(roomId, text)
                        }
                        is PartyMessage.SeekEvent -> {
                            hub.updateState(roomId, msg.positionSecs, isPlaying = null)
                            hub.publish(roomId, text)
                        }
                        is PartyMessage.StateRequest -> {
                            val (pos, playing) = hub.loadState(roomId)
                            val members = activeMembers(db, roomId)
                            val snap = PartyMessage.StateSnapshot(pos, playing, members)
                            send(Frame.Text(PartyJson.encodeToString(PartyMessage.serializer(), snap)))
                        }
                        is PartyMessage.ChatEvent -> {
                            // Body capped at 500 chars; server stamps senderId + ts so
                            // clients can't spoof either. Re-encode and publish — same
                            // channel as Play/Pause/Seek, broadcasted to every subscriber.
                            val stamped = msg.copy(
                                body = msg.body.take(500),
                                senderId = userId,
                                atServerEpochMs = System.currentTimeMillis(),
                            )
                            hub.publish(roomId, PartyJson.encodeToString(PartyMessage.serializer(), stamped))
                        }
                        is PartyMessage.StateSnapshot -> {
                            // ponytail: clients shouldn't author snapshots; silently drop.
                        }
                    }
                }
            } finally {
                pump.cancel()
                hub.unsubscribe(roomId, inbox)
            }
        }
    }
}

private fun ApplicationCall.userIdFromJwt(): UUID =
    UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)

private suspend fun isActiveMember(db: R2dbcDatabase, roomId: UUID, userId: UUID): Boolean =
    suspendTransaction(db) {
        PartyMembers.selectAll()
            .where {
                (PartyMembers.roomId eq roomId) and
                    (PartyMembers.userId eq userId) and
                    PartyMembers.leftAt.isNull()
            }
            .map { 1 }
            .firstOrNull() != null
    }

private suspend fun activeMembers(db: R2dbcDatabase, roomId: UUID): List<String> =
    suspendTransaction(db) {
        PartyMembers.select(PartyMembers.userId)
            .where {
                (PartyMembers.roomId eq roomId) and
                    PartyMembers.leftAt.isNull()
            }
            .map { it[PartyMembers.userId].value.toString() }
            .toList()
    }
