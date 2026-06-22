package wtf.jobin.streaming

import io.ktor.server.auth.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.util.UUID

fun Route.subtitleRoutes(db: R2dbcDatabase, media: wtf.jobin.config.AppConfig.Media) {
    val extractor = SubtitleExtractor(media.ffmpegPath, media.ffprobePath, media.hlsRoot, db)
    authenticate("auth-jwt") {
        get("/media/{media_id}/subtitles") {
            val id = try {
                UUID.fromString(call.parameters["media_id"]!!)
            } catch (_: IllegalArgumentException) {
                throw NotFoundException()
            }
            call.respond(extractor.tracks(id))
        }
    }
}
