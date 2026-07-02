package wtf.jobin.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wtf.jobin.identity.IdentityAccountRepository
import java.util.UUID

// 404 over 403 — don't leak admin surface.
private fun ApplicationCall.assertAdmin() {
    if (principal<JWTPrincipal>()?.payload?.getClaim("admin")?.asBoolean() != true) {
        throw NotFoundException()
    }
}

fun Route.adminUserRoutes(users: UserRepository) {
    authenticate("auth-jwt") {
        get("/admin/users") {
            call.assertAdmin()
            call.respond(users.list().map { it.toView() })
        }
        post("/admin/users/{id}/promote") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<PromoteRequest>()
            val updated = users.setAdmin(id, req.isAdmin) ?: throw NotFoundException()
            call.respond(updated.toView())
        }
        post("/admin/users/{id}/active") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<SetActiveRequest>()
            val u = users.setActive(id, req.active) ?: throw NotFoundException()
            call.respond(u.toView())
        }
        post("/admin/users/{id}/max-rating") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<SetMaxRatingRequest>()
            if (!wtf.jobin.rating.isValidRating(req.maxRating)) throw IllegalArgumentException("invalid rating")
            val u = users.setMaxRating(id, wtf.jobin.rating.normalizeRating(req.maxRating)) ?: throw NotFoundException()
            call.respond(u.toView())
        }
        delete("/admin/users/{id}") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val self = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            if (id == self) throw IllegalArgumentException("cannot delete yourself")
            if (!users.delete(id)) throw NotFoundException()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * #120 security fix: after #150 the JWT subject is an identity_accounts.id, so the users-keyed
 * /admin/users/{id}/max-rating above cannot cap an identity account. This sibling sets the cap on the
 * identity principal itself — the value rating.maxRatingFor now enforces. Same admin gate + rating
 * validation as the users route; reuses SetMaxRatingRequest. 204 on success, 404 if no such account.
 */
fun Route.adminIdentityRoutes(accounts: IdentityAccountRepository) {
    authenticate("auth-jwt") {
        post("/admin/identities/{id}/max-rating") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<SetMaxRatingRequest>()
            if (!wtf.jobin.rating.isValidRating(req.maxRating)) throw IllegalArgumentException("invalid rating")
            if (!accounts.setMaxRating(id, wtf.jobin.rating.normalizeRating(req.maxRating))) throw NotFoundException()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
