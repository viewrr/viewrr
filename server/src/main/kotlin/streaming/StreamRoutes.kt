package wtf.jobin.streaming

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.config.AppConfig
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

// HLS playlist + MPEG-TS segment content types — neither has a builtin in ContentType.
private val M3U8_CT = ContentType.parse("application/vnd.apple.mpegurl")
private val TS_CT = ContentType.parse("video/mp2t")

fun Route.streamRoutes(db: R2dbcDatabase, media: AppConfig.Media) {
    val hlsRoot = Path.of(media.hlsRoot)
    route("/stream") {
        // PartialContent gives us Range support transparently; LocalFileContent on Netty
        // still serves both full and ranged responses via DefaultFileRegion (zero-copy).
        install(PartialContent)
        authenticate("auth-jwt") {
            get("/{media_id}/{file}") {
                val file = call.parameters["file"]!!
                // Path traversal guard: any separator or parent ref → 404 (don't leak).
                if (file.contains('/') || file.contains('\\') || file.contains("..")) {
                    throw NotFoundException()
                }
                val mediaId = try {
                    UUID.fromString(call.parameters["media_id"]!!)
                } catch (_: IllegalArgumentException) {
                    throw NotFoundException()
                }
                // ponytail: one-shot column read; library_id is the only field we need.
                val libraryId = suspendTransaction(db) {
                    MediaItems.selectAll()
                        .where { MediaItems.id eq mediaId }
                        .map { it[MediaItems.libraryId].value }
                        .firstOrNull()
                } ?: throw NotFoundException()

                val target = hlsRoot
                    .resolve(libraryId.toString())
                    .resolve(mediaId.toString())
                    .resolve("hls")
                    .resolve(file)
                if (!Files.isRegularFile(target)) throw NotFoundException()

                val ct = when (file.substringAfterLast('.', "").lowercase()) {
                    "m3u8" -> M3U8_CT
                    "ts" -> TS_CT
                    else -> ContentType.Application.OctetStream
                }
                call.respond(LocalFileContent(target.toFile(), contentType = ct))
            }
        }
    }
}
