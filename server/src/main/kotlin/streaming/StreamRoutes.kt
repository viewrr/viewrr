package wtf.jobin.streaming

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
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

fun Route.streamRoutes(db: R2dbcDatabase, media: AppConfig.Media, stremioKeys: wtf.jobin.stremio.StremioKeys) {
    val hlsRoot = Path.of(media.hlsRoot)
    route("/stream") {
        // PartialContent gives us Range support transparently; LocalFileContent on Netty
        // still serves both full and ranged responses via DefaultFileRegion (zero-copy).
        install(PartialContent)
        // Stremio/HLS: key as a path PREFIX so the playlist's relative segment + variant URIs
        // (seg_000.ts, v0.m3u8) resolve under it. HLS players drop ?query on relative refs.
        get("/k/{key}/{media_id}/{file}") {
            val uid = call.parameters["key"]?.let { stremioKeys.resolve(it) } ?: throw NotFoundException()
            serveHlsFile(call, db, hlsRoot, uid)
        }
        // optional JWT: browser/app clients send Bearer; single-file fetches may pass ?key=
        authenticate("auth-jwt", optional = true) {
            get("/{media_id}/{file}") {
                val uid = call.principal<JWTPrincipal>()?.subject?.let { UUID.fromString(it) }
                    ?: call.request.queryParameters["key"]?.let { stremioKeys.resolve(it) }
                    ?: throw NotFoundException()
                serveHlsFile(call, db, hlsRoot, uid)
            }
        }
    }
}

// Serves one file from {hlsRoot}/{libraryId}/{mediaId}/hls/, parental-gated for `uid`.
private suspend fun serveHlsFile(
    call: io.ktor.server.application.ApplicationCall,
    db: R2dbcDatabase,
    hlsRoot: Path,
    uid: UUID,
) {
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
    // ponytail: one-shot column read; library_id + content_rating in one pass.
    val row = suspendTransaction(db) {
        MediaItems.selectAll()
            .where { MediaItems.id eq mediaId }
            .map { it[MediaItems.libraryId].value to it[MediaItems.contentRating] }
            .firstOrNull()
    } ?: throw NotFoundException()
    val (libraryId, contentRating) = row

    // Parental gate: blocked media 404s — never leak its existence.
    val max = wtf.jobin.rating.maxRatingFor(db, uid)
    if (!wtf.jobin.rating.isVisible(max, contentRating)) throw NotFoundException()

    val target = hlsRoot
        .resolve(libraryId.toString())
        .resolve(mediaId.toString())
        .resolve("hls")
        .resolve(file)
    if (!Files.isRegularFile(target)) throw NotFoundException()

    val ct = when (file.substringAfterLast('.', "").lowercase()) {
        "m3u8" -> M3U8_CT
        "ts" -> TS_CT
        "vtt" -> ContentType.parse("text/vtt")
        "jpg", "jpeg" -> ContentType.Image.JPEG
        else -> ContentType.Application.OctetStream
    }
    call.respond(LocalFileContent(target.toFile(), contentType = ct))
}
