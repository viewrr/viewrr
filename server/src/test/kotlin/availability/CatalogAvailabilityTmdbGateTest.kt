package wtf.jobin.availability

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.ContentUuid
import wtf.jobin.db.Libraries
import wtf.jobin.db.MediaItems
import wtf.jobin.db.Nodes
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #124: the public availability lookup MUST apply the TMDB allowlist. A Title only becomes publicly
 * discoverable-in-the-swarm once TMDB-validated — the same gate the public Stremio catalog uses
 * ([wtf.jobin.media.publicCatalogOp]). Exercised against the real
 * [publicContentUuid] query on in-memory H2, so the gate is proven on the code the route runs — not
 * a copy of it. This is the third "real proof" test.
 */
class CatalogAvailabilityTmdbGateTest {

    private fun freshDb(): R2dbcDatabase = R2dbcDatabase.connect(
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///cat_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1"),
        databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
    )

    private data class Seeded(val public: UUID, val nonTmdb: UUID, val deindexed: UUID)

    private suspend fun seed(now: Instant): Seeded {
        val nodeId = Nodes.insertAndGetId { it[name] = "node"; it[createdAt] = now }.value
        val libraryId = Libraries.insertAndGetId {
            it[Libraries.nodeId] = nodeId
            it[name] = "lib"; it[kind] = "movies"; it[rootPath] = "/data"; it[createdAt] = now
        }.value

        suspend fun insert(path: String, tmdb: Int?, cuuid: UUID?, deindex: Boolean) = MediaItems.insertAndGetId {
            it[MediaItems.libraryId] = libraryId
            it[MediaItems.nodeId] = nodeId
            it[title] = path
            it[originalPath] = path
            it[tmdbId] = tmdb
            it[contentUuid] = cuuid
            it[deindexed] = deindex
            it[createdAt] = now
            it[updatedAt] = now
        }.value

        // TMDB-validated, not de-indexed -> publicly available.
        val public = insert("/data/a.mkv", 550, ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE), false)
        // No TMDB match -> private-by-default, never publicly available.
        val nonTmdb = insert("/data/b.mkv", null, null, false)
        // TMDB match but operator-de-indexed -> hidden from public availability.
        val deindexed = insert("/data/c.mkv", 603, ContentUuid.forTmdb(603, ContentUuid.Kind.MOVIE), true)
        return Seeded(public, nonTmdb, deindexed)
    }

    @Test
    fun tmdbGateExcludesNonTmdbAndDeindexedFromPublicAvailability() = runBlocking {
        val db = freshDb()
        val ids = suspendTransaction(db) {
            SchemaUtils.create(Nodes, Libraries, MediaItems)
            seed(Instant.now())
        }

        // TMDB-validated Title -> its content address is returned (client can hash it to a topic).
        assertEquals(
            ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE),
            publicContentUuid(db, ids.public),
        )
        // Non-TMDB Title -> excluded (null), so no public availability.
        assertNull(publicContentUuid(db, ids.nonTmdb))
        // De-indexed Title -> excluded (null) despite a TMDB match.
        assertNull(publicContentUuid(db, ids.deindexed))
        // Unknown id -> null (never leaks existence).
        assertNull(publicContentUuid(db, UUID.randomUUID()))
    }
}
