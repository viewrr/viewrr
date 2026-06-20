package wtf.jobin.auth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(svc: AuthService) {
    route("/auth") {
        post("/register") {
            try {
                call.respond(HttpStatusCode.Created, svc.register(call.receive()))
            } catch (e: AuthError.UsernameTaken) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to e.message))
            }
        }
        post("/login") {
            try {
                call.respond(svc.login(call.receive()))
            } catch (e: AuthError.InvalidCredentials) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
            }
        }
        post("/refresh") {
            try {
                call.respond(svc.refresh(call.receive<RefreshRequest>().refreshToken))
            } catch (e: AuthError.InvalidRefresh) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to e.message))
            }
        }
        post("/logout") {
            svc.logout(call.receive<RefreshRequest>().refreshToken)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
