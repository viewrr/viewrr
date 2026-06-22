package wtf.jobin.scanner

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.rating.isValidRating
import wtf.jobin.rating.normalizeRating
import java.util.UUID

@Serializable
data class SetContentRatingRequest(val contentRating: String? = null)

// 404 over 403 — don't leak admin surface.
private fun ApplicationCall.assertAdmin() {
    if (principal<JWTPrincipal>()?.payload?.getClaim("admin")?.asBoolean() != true) {
        throw NotFoundException()
    }
}

fun Route.mediaAdminRoutes(db: R2dbcDatabase) {
    authenticate("auth-jwt") {
        patch("/admin/media/{id}") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<SetContentRatingRequest>()
            if (!isValidRating(req.contentRating)) throw IllegalArgumentException("invalid rating")
            val n = suspendTransaction(db) {
                MediaItems.update({ MediaItems.id eq id }) {
                    it[MediaItems.contentRating] = normalizeRating(req.contentRating)
                }
            }
            if (n == 0) throw NotFoundException()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
