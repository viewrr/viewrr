package wtf.jobin.recs

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class RefreshRecsRequest(val userId: String, val topK: Int)

@Serializable
data class RefreshRecsResponse(val written: Int)

fun Route.adminRecsRoutes(client: RecEngineClient) {
    authenticate("auth-jwt") {
        post("/admin/recs/refresh") {
            val isAdmin = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("admin")
                ?.asBoolean() == true
            // 404 over 403 — don't leak admin surface.
            if (!isAdmin) throw NotFoundException()
            val req = call.receive<RefreshRecsRequest>()
            val userId = UUID.fromString(req.userId)
            val written = client.refreshUserRecs(userId, req.topK)
            call.respond(RefreshRecsResponse(written))
        }
    }
}
