package wtf.jobin.scanner

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.scannerRoutes(scanner: MediaScanner) {
    authenticate("auth-jwt") {
        post("/admin/libraries/{id}/scan") {
            val isAdmin = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("admin")
                ?.asBoolean() == true
            // 404 over 403 — don't leak admin surface.
            if (!isAdmin) throw NotFoundException()
            val id = UUID.fromString(call.parameters["id"]!!)
            call.respond(scanner.scan(id))
        }
    }
}
