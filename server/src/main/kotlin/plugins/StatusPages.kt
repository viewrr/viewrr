package wtf.jobin.plugins

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<NotFoundException> { call, _ ->
            call.respond(HttpStatusCode.NotFound)
        }
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad request")))
        }
        exception<IllegalStateException> { call, e ->
            // MediaScanner.scan throws this when library_id doesn't exist.
            call.respond(HttpStatusCode.NotFound, mapOf("error" to (e.message ?: "not found")))
        }
        // gRPC transport / status failures from the Python rec engine → 503.
        // StatusException (checked) and StatusRuntimeException (unchecked) share no
        // common ancestor below Throwable, so register both.
        exception<StatusException> { call, e ->
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "rec engine unavailable: ${e.status.code}"))
        }
        exception<StatusRuntimeException> { call, e ->
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "rec engine unavailable: ${e.status.code}"))
        }
    }
}
