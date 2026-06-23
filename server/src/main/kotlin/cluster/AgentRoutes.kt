package wtf.jobin.cluster

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val enrollmentSecret: String,
    val name: String,
    val meshAddress: String? = null,
    val clientAddress: String? = null,
)

@Serializable
data class RegisterResponse(val nodeId: String, val token: String)

/**
 * Phase 14 (#69). Unauthenticated by JWT — gated by the enrollment secret in the body.
 * The Agent persists the returned nodeId + token and presents the token thereafter.
 * ponytail: mounted unconditionally for now; Hub-only role-gating lands with #68 gating.
 */
fun Route.agentRoutes(registry: NodeRegistry) {
    post("/agent/register") {
        val req = call.receive<RegisterRequest>()
        val r = try {
            registry.register(req.enrollmentSecret, req.name, req.meshAddress, req.clientAddress)
        } catch (_: NodeRegistry.BadEnrollment) {
            return@post call.respond(HttpStatusCode.Unauthorized)
        }
        call.respond(RegisterResponse(r.nodeId.toString(), r.token))
    }
}
