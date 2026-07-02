package wtf.jobin.worklet

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Libraries
import wtf.jobin.db.MediaCopies
import wtf.jobin.db.MediaItems
import wtf.jobin.db.Nodes
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** #121 slice 3: only content_uuid-bearing titles that actually have a copy are announced. H2, no network. */
class AnnounceRepositoryTest {
    private fun freshDb(): R2dbcDatabase {
        val name = "announce_" + UUID.randomUUID().toString().replace("-", "")
        return R2dbcDatabase.connect(
            connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1"),
            databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
        )
    }

    @Test
    fun announcesOnlyTitlesWithBothContentUuidAndCopy() = runBlocking {
        val db = freshDb()
        val repo = AnnounceRepository(db)
        val withCopy = UUID.fromString("bc592db3-805a-58ff-9f95-b90687681997")

        suspendTransaction(db) {
            SchemaUtils.create(Nodes, Libraries, MediaItems, MediaCopies)
            val now = Instant.now()
            val nodeId = Nodes.insertAndGetId { it[name] = "n"; it[createdAt] = now }.value
            val libId = Libraries.insertAndGetId {
                it[Libraries.nodeId] = nodeId; it[name] = "movies"; it[kind] = "movie"
                it[rootPath] = "/m"; it[createdAt] = now
            }.value

            suspend fun media(title: String, path: String, uuid: UUID?) = MediaItems.insertAndGetId {
                it[libraryId] = libId; it[MediaItems.nodeId] = nodeId; it[MediaItems.title] = title
                it[originalPath] = path; it[contentUuid] = uuid; it[createdAt] = now; it[updatedAt] = now
            }.value
            suspend fun copy(titleId: UUID, path: String) = MediaCopies.insert {
                it[MediaCopies.titleId] = titleId; it[MediaCopies.nodeId] = nodeId
                it[originalPath] = path; it[createdAt] = now; it[updatedAt] = now
            }

            val a = media("A", "/m/a.mkv", withCopy)   // uuid + copy(x2) -> announced once
            copy(a, "/m/a.mkv"); copy(a, "/m/a-1080.mkv")
            val b = media("B", "/m/b.mkv", UUID.randomUUID()) // uuid, NO copy -> excluded
            val c = media("C", "/m/c.mkv", null)              // copy but NO uuid -> excluded
            copy(c, "/m/c.mkv")

            val result = repo.localContentUuids()
            assertEquals(listOf("bc592db3805a58ff9f95b90687681997"), result) // undashed, deduped, only A
        }
    }
}
