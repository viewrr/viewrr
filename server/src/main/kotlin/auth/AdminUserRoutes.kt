package wtf.jobin.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
