package wtf.jobin.identity

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * #120: self-custody identity endpoints. Mounted ALONGSIDE /auth (Keycloak/argon2) — this adds
 * a key-based path, it does not remove the existing one.
 */
fun Route.identityRoutes(svc: IdentityService) {
    route("/identity") {
        // Register a public key. Idempotent: an already-registered key returns 200, never a dup.
        post("/register") {
            try {
                val (view, created) = svc.register(call.receive())
                call.respond(if (created) HttpStatusCode.Created else HttpStatusCode.OK, view)
            } catch (e: IdentityError.BadSignature) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
            }
        }
        // Get a single-use challenge nonce to sign.
        get("/challenge") {
            call.respond(svc.issueChallenge())
        }
        // Prove key ownership over the challenge -> receive the app's normal session tokens.
        post("/verify") {
            try {
                call.respond(svc.verify(call.receive()))
            } catch (e: IdentityError) {
                // BadSignature / InvalidChallenge / UnknownAccount all collapse to 401 — no oracle
                // that distinguishes "not registered" from "bad signature" to an unauthenticated caller.
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
            }
        }
    }
}
