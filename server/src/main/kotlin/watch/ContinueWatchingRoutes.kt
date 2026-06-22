package wtf.jobin.watch

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 100

fun Route.continueWatchingRoutes(svc: ContinueWatchingService) {
    authenticate("auth-jwt") {
        get("/me/continue-watching") {
            val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val requested = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
            val limit = requested.coerceIn(1, MAX_LIMIT)
            call.respond(svc.forUser(userId, limit))
        }
    }
}
