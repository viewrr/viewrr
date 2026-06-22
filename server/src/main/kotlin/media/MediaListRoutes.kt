package wtf.jobin.media

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
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
)

/**
 * Flat browse of every importable video the caller is allowed to see (parental-filtered).
 * Search (`/media/search`) needs a query and series (`/series`) only covers TV — this is the
 * "show me everything" list. `?limit=` caps the page (default 200).
 */
fun Route.mediaListRoutes(db: R2dbcDatabase) {
    authenticate("auth-jwt") {
        get("/media") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val max = maxRatingFor(db, uid)
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 200
            val items = suspendTransaction(db) {
                MediaItems.selectAll()
                    .orderBy(MediaItems.cleanTitle to SortOrder.ASC)
                    .map {
                        MediaListItem(
                            id = it[MediaItems.id].value.toString(),
                            title = it[MediaItems.title],
                            cleanTitle = it[MediaItems.cleanTitle],
                            showTitle = it[MediaItems.showTitle],
                            seasonNumber = it[MediaItems.seasonNumber],
                            episodeNumber = it[MediaItems.episodeNumber],
                            year = it[MediaItems.year]?.toInt(),
                            durationSecs = it[MediaItems.durationSecs],
                            hlsPath = it[MediaItems.hlsPath],
                            contentRating = it[MediaItems.contentRating],
                        )
                    }.toList()
            }
            // ponytail: filter + cap in Kotlin; libraries are small. Push the rating predicate
            // into SQL only when a single library outgrows an in-memory list.
            call.respond(items.filter { isVisible(max, it.contentRating) }.take(limit))
        }
    }
}
