package wtf.jobin.media

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.rating.isVisible
import wtf.jobin.rating.maxRatingFor
import java.util.UUID

@Serializable
data class MediaListItem(
    val id: String,
    val title: String,
    val cleanTitle: String?,
    val showTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val year: Int?,
    val durationSecs: Int?,
    val hlsPath: String?,
    val contentRating: String?,
    // TMDb enrichment (V9) — the art clients render. Null until enriched.
    val poster: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
)

private fun ResultRow.toMediaItem() = MediaListItem(
    id = this[MediaItems.id].value.toString(),
    title = this[MediaItems.title],
    cleanTitle = this[MediaItems.cleanTitle],
    showTitle = this[MediaItems.showTitle],
    seasonNumber = this[MediaItems.seasonNumber],
    episodeNumber = this[MediaItems.episodeNumber],
    year = this[MediaItems.year]?.toInt(),
    durationSecs = this[MediaItems.durationSecs],
    hlsPath = this[MediaItems.hlsPath],
    contentRating = this[MediaItems.contentRating],
    poster = this[MediaItems.poster],
    backdrop = this[MediaItems.backdrop],
    overview = this[MediaItems.overview],
)

/**
 * Flat browse + single-item detail for first-party clients (parental-filtered).
 * `/media?sort=title|createdAt&order=asc|desc&limit=N` — `createdAt desc` is the
 * "Recently Added" row. `/media/{id}` is the detail endpoint (#109).
 */
fun Route.mediaListRoutes(db: R2dbcDatabase) {
    authenticate("auth-jwt") {
        get("/media") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val max = maxRatingFor(db, uid)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
            val col = when (call.request.queryParameters["sort"]) {
                "createdAt" -> MediaItems.createdAt
                else -> MediaItems.cleanTitle
            }
            val order = if (call.request.queryParameters["order"] == "desc") SortOrder.DESC else SortOrder.ASC
            val items = suspendTransaction(db) {
                MediaItems.selectAll().orderBy(col to order).map { it.toMediaItem() }.toList()
            }
            // ponytail: rating filter + cap in Kotlin; libraries are small.
            call.respond(items.filter { isVisible(max, it.contentRating) }.take(limit))
        }

        get("/media/{id}") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val id = UUID.fromString(call.parameters["id"]!!)
            val max = maxRatingFor(db, uid)
            val item = suspendTransaction(db) {
                MediaItems.selectAll().where { MediaItems.id eq id }.map { it.toMediaItem() }.firstOrNull()
            } ?: throw NotFoundException()
            // 404 (not 403) when parental-hidden — don't leak existence.
            if (!isVisible(max, item.contentRating)) throw NotFoundException()
            call.respond(item)
        }
    }
}
