package wtf.jobin.media

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.auth.jwt.*
import java.util.UUID

fun Route.mediaSearchRoutes(svc: MediaSearchService) {
    authenticate("auth-jwt") {
        get("/media/search") {
            val q = call.request.queryParameters["q"]
                ?: throw IllegalArgumentException("q is required")
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            // Blank-string + non-positive limit checks live in MediaSearchService.search;
            // StatusPages maps the IllegalArgumentException to 400.
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            call.respond(svc.search(q, limit, uid))
        }
    }
}
