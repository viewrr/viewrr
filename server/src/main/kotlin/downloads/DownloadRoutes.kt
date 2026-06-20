package wtf.jobin.downloads

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@Serializable
data class DownloadPrepareRequest(val deviceId: String)

@Serializable
data class DownloadPrepareResponse(val url: String, val expiresAt: String)

fun Route.downloadRoutes(svc: DownloadService, publicBaseUrl: String) {
    route("/downloads") {
        authenticate("auth-jwt") {
            post("/{mediaId}") {
                val req = call.receive<DownloadPrepareRequest>()
                require(req.deviceId.isNotBlank()) { "deviceId must not be blank" }
                require(req.deviceId.length <= 128) { "deviceId must be <= 128 chars" }
                // UUID.fromString → IllegalArgumentException → StatusPages → 400.
                val mediaId = UUID.fromString(call.parameters["mediaId"]!!)
                val userId = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
                val prepared = svc.prepare(mediaId, userId, req.deviceId)
                val token = URLEncoder.encode(prepared.token, StandardCharsets.UTF_8)
                call.respond(
                    DownloadPrepareResponse(
                        url = "$publicBaseUrl/downloads/file?token=$token",
                        expiresAt = prepared.tokenExpiresAt.toString(),
                    ),
                )
            }
        }

        // Public — auth is the signed token itself, not a header.
        get("/file") {
            val token = call.request.queryParameters["token"]
            if (token.isNullOrBlank()) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            val downloadId = svc.verify(token)
            if (downloadId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }
            val row = svc.lookup(downloadId) ?: throw NotFoundException()
            if (!row.expiresAt.isAfter(Instant.now())) {
                call.respond(HttpStatusCode.Gone)
                return@get
            }
            val path = row.filePath
            if (path.isNullOrBlank()) throw NotFoundException()
            val file = File(path)
            if (!file.isFile) throw NotFoundException()
            call.respondFile(file)
        }
    }
}
