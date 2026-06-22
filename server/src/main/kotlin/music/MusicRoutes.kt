package wtf.jobin.music

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.io.File
import java.util.UUID

@Serializable
data class AlbumView(val album: String, val trackCount: Int, val artist: String? = null)

@Serializable
data class TrackView(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationSecs: Int? = null,
)

fun Route.musicRoutes(db: R2dbcDatabase) {
    val repo = MusicRepository(db)
    route("/music") {
        // PartialContent gives Range support for the original-file audio serve below.
        install(PartialContent)
        authenticate("auth-jwt") {
            get("/albums") {
                call.respond(repo.albums().map { AlbumView(it.album, it.trackCount, it.artist) })
            }

            get("/albums/{album}/tracks") {
                val album = call.parameters["album"]!!
                val tracks = repo.tracksByAlbum(album)
                if (tracks.isEmpty()) throw NotFoundException()
                call.respond(tracks.map { it.toView() })
            }

            get("/tracks/{id}/audio") {
                val id = try {
                    UUID.fromString(call.parameters["id"]!!)
                } catch (_: IllegalArgumentException) {
                    throw NotFoundException()
                }
                val track = repo.track(id) ?: throw NotFoundException()
                val file = File(track.originalPath)
                if (!file.isFile) throw NotFoundException()
                val ct = track.mimeType?.let { ContentType.parse(it) }
                    ?: ContentType.Application.OctetStream
                call.respond(LocalFileContent(file, contentType = ct))
            }
        }
    }
}

private fun TrackRow.toView() = TrackView(
    id = id.toString(),
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    trackNumber = trackNumber,
    discNumber = discNumber,
    durationSecs = durationSecs,
)
