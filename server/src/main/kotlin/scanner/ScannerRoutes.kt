package wtf.jobin.scanner

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID
import wtf.jobin.music.MusicScanner

@Serializable
data class TranscodeResponse(val mediaId: String, val hlsPath: String)

fun Route.scannerRoutes(scanner: MediaScanner, musicScanner: MusicScanner) {
    authenticate("auth-jwt") {
        post("/admin/libraries/{id}/scan") {
            val isAdmin = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("admin")
                ?.asBoolean() == true
            // 404 over 403 — don't leak admin surface.
            if (!isAdmin) throw NotFoundException()
            val id = UUID.fromString(call.parameters["id"]!!)
            // Mixed folders: scan video + music. Disjoint tables, so neither prunes the other.
            val v = scanner.scan(id)
            val m = musicScanner.scan(id)
            call.respond(ScanResult(v.added + m.added, v.removed + m.removed, v.skipped + m.skipped))
        }
    }
}

fun Route.mediaRoutes(transcoder: HlsTranscoder) {
    authenticate("auth-jwt") {
        post("/admin/media/{id}/transcode") {
            val isAdmin = call.principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("admin")
                ?.asBoolean() == true
            // 404 over 403 — don't leak admin surface.
            if (!isAdmin) throw NotFoundException()
            val id = UUID.fromString(call.parameters["id"]!!)
            val playlist = transcoder.transcode(id)
            call.respond(TranscodeResponse(id.toString(), playlist.toString()))
        }
    }
}
