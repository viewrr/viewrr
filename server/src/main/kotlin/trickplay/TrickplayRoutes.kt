package wtf.jobin.trickplay

import io.ktor.server.auth.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import wtf.jobin.config.AppConfig
import java.util.UUID

fun Route.trickplayRoutes(db: R2dbcDatabase, media: AppConfig.Media) {
    val gen = TrickplayGenerator(media.ffmpegPath, media.hlsRoot, db)
    authenticate("auth-jwt") {
        get("/media/{media_id}/trickplay") {
            val id = try {
                UUID.fromString(call.parameters["media_id"]!!)
            } catch (_: IllegalArgumentException) {
                throw NotFoundException()
            }
            val info = gen.ensure(id) ?: throw NotFoundException()
            call.respond(info)
        }
    }
}
