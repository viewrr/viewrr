package wtf.jobin.media

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.db.WatchEvents
import wtf.jobin.rating.isVisible
import wtf.jobin.rating.maxRatingFor
import wtf.jobin.stremio.StremioKeys
import java.util.UUID

@Serializable
data class PlaybackResolve(
    val url: String,
    val type: String = "hls",
    val startPositionSecs: Int = 0,
    val subtitlesUrl: String,
    val trickplayUrl: String,
)

/**
 * #111 — the single playback call a first-party client makes. Resolves a title to a
 * playable HLS URL (path-keyed by the caller's per-device stremio-key, so the player
 * itself needs no bearer) plus resume position and sidecar URLs.
 *
 * ponytail: returns the Hub HLS URL. Capability-profile targeting + locality (serve from
 * a same-LAN Node) layer in here in Phase 15 without changing the client contract.
 */
fun Route.playbackRoutes(db: R2dbcDatabase, stremioKeys: StremioKeys, publicBaseUrl: String) {
    authenticate("auth-jwt") {
        get("/playback/{id}") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val id = UUID.fromString(call.parameters["id"]!!)

            // Pair the (nullable) rating with a presence marker so "no row" is
            // distinguishable from "row with null rating".
            val found = suspendTransaction(db) {
                MediaItems.selectAll().where { MediaItems.id eq id }
                    .map { it[MediaItems.contentRating] to Unit }
                    .firstOrNull()
            } ?: throw NotFoundException()
            // 404 (not 403) on parental-hidden too — don't leak existence.
            if (!isVisible(maxRatingFor(db, uid), found.first)) throw NotFoundException()

            val key = stremioKeys.keyFor(uid)
            val base = publicBaseUrl.trimEnd('/')
            val start = suspendTransaction(db) {
                WatchEvents.select(WatchEvents.positionSecs)
                    .where { (WatchEvents.userId eq uid) and (WatchEvents.mediaId eq id) }
                    .orderBy(WatchEvents.createdAt to SortOrder.DESC)
                    .limit(1)
                    .map { it[WatchEvents.positionSecs] }
                    .firstOrNull() ?: 0
            }
            call.respond(
                PlaybackResolve(
                    url = "$base/stream/k/$key/$id/playlist.m3u8",
                    startPositionSecs = start,
                    subtitlesUrl = "$base/media/$id/subtitles",
                    trickplayUrl = "$base/media/$id/trickplay",
                ),
            )
        }
    }
}
