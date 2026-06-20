package wtf.jobin.auth

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.adminUserRoutes(users: UserRepository) {
    authenticate("auth-jwt") {
        post("/admin/users/{id}/promote") {
            val isAdmin = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("admin")
                ?.asBoolean() == true
            // 404 over 403 — don't leak admin surface.
            if (!isAdmin) throw NotFoundException()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<PromoteRequest>()
            val updated = users.setAdmin(id, req.isAdmin) ?: throw NotFoundException()
            call.respond(updated.toView())
        }
    }
}
