package wtf.jobin.media

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.db.WatchEvents
import wtf.jobin.rating.isVisible
import wtf.jobin.rating.maxRatingFor
import java.util.UUID

/**
 * #109 — the two home rows that aren't a plain list:
 *  - /home/top      Top 10 by watch count (falls back to recently-added when there's no history yet)
 *  - /home/featured items with backdrop art (falls back to recently-added)
 * Other rows compose client-side from /media, /me/recommendations, /me/continue-watching.
 *
 * ponytail: "top" = raw watch_events count; "featured" = has-a-backdrop. Real popularity
 * windowing + editorial curation are later; the row shapes won't change.
 */
fun Route.homeRoutes(db: R2dbcDatabase) {
    authenticate("auth-jwt") {
        get("/home/top") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val max = maxRatingFor(db, uid)
            val cnt = WatchEvents.mediaId.count()
            val ids = suspendTransaction(db) {
                WatchEvents.select(WatchEvents.mediaId, cnt)
                    .groupBy(WatchEvents.mediaId)
                    .orderBy(cnt to SortOrder.DESC)
                    .limit(10)
                    .map { it[WatchEvents.mediaId].value }
                    .toList()
            }
            val items = if (ids.isEmpty()) recent(db, 10) else byIds(db, ids)
            call.respond(items.filter { isVisible(max, it.contentRating) }.take(10))
        }

        get("/home/featured") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            val max = maxRatingFor(db, uid)
            val withArt = suspendTransaction(db) {
                // #128: public home row — de-indexed + non-TMDB Titles excluded (publicCatalogOp).
                MediaItems.selectAll().where { MediaItems.backdrop.isNotNull() and publicCatalogOp() }
                    .orderBy(MediaItems.createdAt to SortOrder.DESC)
                    .limit(8)
                    .map { it.toMediaItem() }
                    .toList()
            }
            val items = withArt.ifEmpty { recent(db, 8) }
            call.respond(items.filter { isVisible(max, it.contentRating) }.take(8))
        }
    }
}

private suspend fun recent(db: R2dbcDatabase, n: Int): List<MediaListItem> = suspendTransaction(db) {
    // #128: recently-added fallback is a public row — apply the discovery gate.
    MediaItems.selectAll().where { publicCatalogOp() }
        .orderBy(MediaItems.createdAt to SortOrder.DESC).limit(n)
        .map { it.toMediaItem() }.toList()
}

// Fetch + reorder to match the ranked id list. Library is small, so a full read is fine.
private suspend fun byIds(db: R2dbcDatabase, ids: List<UUID>): List<MediaListItem> {
    val byId = suspendTransaction(db) {
        // #128: drop de-indexed/non-TMDB Titles so they can't ride "top" back into a public row.
        MediaItems.selectAll().where { publicCatalogOp() }.map { it.toMediaItem() }.toList()
    }.associateBy { UUID.fromString(it.id) }
    return ids.mapNotNull { byId[it] }
}
