package wtf.jobin.editorial

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

/**
 * Editorial endpoints:
 *   GET  /media/{id}/reviews        -> { reviews:[...], highlights:[...] }  (any authed user)
 *   POST /admin/editorial/refresh   -> RefreshSummary                        (admin only)
 *
 * ponytail: reviews/highlights are public metadata (critic links, award badges), so the read path
 * only requires a valid session — no parental gate. The item's own visibility is enforced on the
 * media detail/list endpoints; a hidden id here just returns an empty bundle, leaking nothing.
 */
fun Route.editorialRoutes(repo: EditorialRepository, ingest: EditorialIngestService) {
    authenticate("auth-jwt") {
        get("/media/{id}/reviews") {
            call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val id = UUID.fromString(call.parameters["id"]!!)
            call.respond(repo.bundleFor(id))
        }

        // Manual trigger. ponytail: no scheduler wired — the repo has no reusable cron pattern and the
        // brief said not to build one. Drive this from an external cron / arr hook, or add an interval
        // here later if a scheduler lands.
        post("/admin/editorial/refresh") {
            val admin = call.principal<JWTPrincipal>()?.payload?.getClaim("admin")?.asBoolean() ?: false
            if (!admin) return@post call.respond(HttpStatusCode.Forbidden)
            call.respond(ingest.refresh())
        }
    }
}
