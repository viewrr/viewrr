package wtf.jobin.party

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreatePartyRoomRequest(val mediaId: String)

@Serializable
data class JoinPartyRoomRequest(val joinCode: String)

fun Route.partyRoomRoutes(repo: PartyRoomRepository) {
    authenticate("auth-jwt") {
        route("/party-rooms") {
            post {
                val ownerId = call.userIdFromJwt()
                val body = call.receive<CreatePartyRoomRequest>()
                val mediaId = UUID.fromString(body.mediaId)
                val room = repo.create(ownerId, mediaId)
                call.respond(HttpStatusCode.Created, room.toView())
            }

            post("/join") {
                val userId = call.userIdFromJwt()
                val body = call.receive<JoinPartyRoomRequest>()
                // findOpenByJoinCode filters out closed rooms — same 404 path either way.
                val room = repo.findOpenByJoinCode(body.joinCode) ?: throw NotFoundException()
                repo.join(room.id, userId)
                call.respond(room.toView())
            }

            post("/{id}/leave") {
                val userId = call.userIdFromJwt()
                val roomId = UUID.fromString(call.parameters["id"]!!)
                repo.leave(roomId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/close") {
                val callerId = call.userIdFromJwt()
                val roomId = UUID.fromString(call.parameters["id"]!!)
                val room = repo.findById(roomId) ?: throw NotFoundException()
                if (room.ownerId != callerId) {
                    call.respond(HttpStatusCode.Forbidden)
                    return@post
                }
                val closed = repo.close(roomId) ?: throw NotFoundException()
                call.respond(closed.toView())
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.userIdFromJwt(): UUID =
    UUID.fromString(principal<JWTPrincipal>()!!.payload.subject)
