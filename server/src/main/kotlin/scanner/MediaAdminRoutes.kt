package wtf.jobin.scanner

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.rating.isValidRating
import wtf.jobin.rating.normalizeRating
import java.util.UUID

@Serializable
data class SetContentRatingRequest(val contentRating: String? = null)

@Serializable
data class BackfillResult(val scanned: Int, val enriched: Int, val missed: Int)

// #128: operator de-index toggle payload.
@Serializable
class DeindexRequest(val deindexed: Boolean)

// 404 over 403 — don't leak admin surface.
private fun ApplicationCall.assertAdmin() {
    if (principal<JWTPrincipal>()?.payload?.getClaim("admin")?.asBoolean() != true) {
        throw NotFoundException()
    }
}

fun Route.mediaAdminRoutes(db: R2dbcDatabase, tmdb: TmdbClient) {
    authenticate("auth-jwt") {
        // One-shot TMDb backfill for movies that predate enrichment (poster IS NULL).
        // ponytail: serial HTTP in the request; fine for a few-hundred-item personal lib.
        post("/admin/media/backfill-tmdb") {
            call.assertAdmin()
            if (!tmdb.enabled) throw IllegalStateException("TMDB_API_KEY not set")
            val rows = suspendTransaction(db) {
                MediaItems.selectAll()
                    .where { MediaItems.showTitle.isNull() and MediaItems.poster.isNull() }
                    .map { Triple(it[MediaItems.id].value, it[MediaItems.cleanTitle] ?: it[MediaItems.title], it[MediaItems.year]?.toInt()) }
                    .toList()
            }
            var enriched = 0
            for ((id, title, year) in rows) {
                val meta = tmdb.lookupMovie(title, year) ?: continue
                suspendTransaction(db) {
                    MediaItems.update({ MediaItems.id eq id }) {
                        it[MediaItems.tmdbId] = meta.tmdbId
                        it[MediaItems.poster] = meta.poster
                        it[MediaItems.backdrop] = meta.backdrop
                        it[MediaItems.overview] = meta.overview
                    }
                }
                enriched++
            }
            call.respond(BackfillResult(rows.size, enriched, rows.size - enriched))
        }

        patch("/admin/media/{id}") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<SetContentRatingRequest>()
            if (!isValidRating(req.contentRating)) throw IllegalArgumentException("invalid rating")
            val n = suspendTransaction(db) {
                MediaItems.update({ MediaItems.id eq id }) {
                    it[MediaItems.contentRating] = normalizeRating(req.contentRating)
                }
            }
            if (n == 0) throw NotFoundException()
            call.respond(HttpStatusCode.NoContent)
        }

        // #128: operator de-index toggle. Hides the Title from public discovery
        // (browse/search/home/Stremio) without deleting the row or its Copies;
        // set deindexed=false to re-index. TMDB allowlist gate is separate and
        // automatic (a Title with no tmdb_id stays private regardless).
        // ponytail: reuses assertAdmin + the same update path as content-rating.
        post("/admin/media/{id}/deindex") {
            call.assertAdmin()
            val id = UUID.fromString(call.parameters["id"]!!)
            val req = call.receive<DeindexRequest>()
            val n = suspendTransaction(db) {
                MediaItems.update({ MediaItems.id eq id }) {
                    it[MediaItems.deindexed] = req.deindexed
                }
            }
            if (n == 0) throw NotFoundException()
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
