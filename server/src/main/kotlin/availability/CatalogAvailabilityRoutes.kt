package wtf.jobin.availability

import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.media.publicCatalogOp
import java.util.UUID

@Serializable
data class CatalogAvailability(
    val id: String,
    val contentUuid: String, // #124: the DHT content address a client hashes to find the swarm
    val topicHex: String,    // convenience: hash(contentUuid), the exact Hyperswarm topic to join
)

/**
 * #124 (P2P-ADR 0008): the public catalog read a client uses to go from a Title id to the swarm.
 *
 * `GET /v0/catalog/{id}/availability` -> `{ id, contentUuid, topicHex }`. The client joins `topicHex`
 * to find peers holding the content — nobody is told WHO holds it. Deliberately UNAUTHENTICATED and
 * PUBLIC: this is the shared/advertised surface (same audience as the Stremio catalog), so it MUST
 * apply the full TMDB allowlist gate.
 *
 * TMDB gate, reused not re-implemented: the row is fetched under [publicCatalogOp]
 * (`deindexed = false AND tmdb_id IS NOT NULL`) — the SAME predicate the public Stremio catalog uses.
 * A non-TMDB (or de-indexed) Title therefore 404s here exactly as it is absent from the public
 * catalog: no TMDB validation, no public availability. 404 (not 403) so we never leak existence.
 */
fun Route.catalogAvailabilityRoutes(db: R2dbcDatabase) {
    get("/v0/catalog/{id}/availability") {
        val id = runCatching { UUID.fromString(call.parameters["id"]!!) }.getOrNull()
            ?: throw NotFoundException()
        val uuid = publicContentUuid(db, id) ?: throw NotFoundException() // absent, non-TMDB, or de-indexed
        call.respond(
            CatalogAvailability(
                id = id.toString(),
                contentUuid = uuid.toString(),
                topicHex = SwarmTopic.topicHex(uuid),
            )
        )
    }
}

/**
 * The content_uuid of a Title IF it is publicly available — i.e. it passes the same TMDB allowlist
 * gate ([publicCatalogOp]) as the public Stremio catalog. Returns null for an
 * unknown id, a de-indexed Title, or a non-TMDB (unvalidated) Title. This is the single choke point
 * the route and its test both exercise, so the TMDB gate is proven on the real query.
 */
internal suspend fun publicContentUuid(db: R2dbcDatabase, id: UUID): UUID? = suspendTransaction(db) {
    MediaItems
        .select(MediaItems.contentUuid)
        .where { (MediaItems.id eq id) and publicCatalogOp() }
        .map { it[MediaItems.contentUuid] }
        .firstOrNull() // null == unknown id, non-TMDB, or de-indexed (all: no public availability)
}
