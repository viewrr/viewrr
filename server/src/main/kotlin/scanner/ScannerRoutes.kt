package wtf.jobin.scanner

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import wtf.jobin.auth.requireAdmin
import java.util.UUID

@Serializable
data class ScanResponse(val added: Int, val removed: Int, val skipped: Int)

fun Route.scannerRoutes(svc: ScannerService) {
    authenticate("auth-jwt") {
        post("/admin/libraries/{id}/scan") {
            call.requireAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val r = svc.scanLibrary(id)
            call.respond(ScanResponse(r.added, r.removed, r.skipped))
        }
    }
}
