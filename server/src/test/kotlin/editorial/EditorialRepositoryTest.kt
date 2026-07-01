package wtf.jobin.editorial

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Libraries
import wtf.jobin.db.MediaItems
import wtf.jobin.db.MovieHighlights
import wtf.jobin.db.MovieReviews
import wtf.jobin.db.Nodes
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the real storage + read path against in-memory H2 R2DBC (no live network, no Postgres).
 * The whole test runs in one transaction so the repo's nested suspendTransaction joins it and the
 * seeded FK parent rows are visible to the inserts.
 */
class EditorialRepositoryTest {

    private fun freshDb(): R2dbcDatabase {
        val name = "editorial_" + UUID.randomUUID().toString().replace("-", "")
        return R2dbcDatabase.connect(
            connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1"),
            databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
        )
    }

    @Test fun storesAndReadsReviewsAndHighlights() = runBlocking {
        val db = freshDb()
        val repo = EditorialRepository(db)

        suspendTransaction(db) {
            SchemaUtils.create(Nodes, Libraries, MediaItems, MovieReviews, MovieHighlights)

            val now = Instant.now()
            val nodeId = Nodes.insertAndGetId {
                it[name] = "test-node"
                it[createdAt] = now
            }.value
            val libId = Libraries.insertAndGetId {
                it[Libraries.nodeId] = nodeId
                it[name] = "movies"
                it[kind] = "movie"
                it[rootPath] = "/movies"
                it[createdAt] = now
            }.value
            val mediaId = MediaItems.insertAndGetId {
                it[libraryId] = libId
                it[MediaItems.nodeId] = nodeId
                it[title] = "Dune.2021.2160p"
                it[cleanTitle] = "Dune"
                it[year] = 2021
                it[originalPath] = "/movies/dune.mkv"
                it[createdAt] = now
                it[updatedAt] = now
            }.value

            // allTitles feeds the fuzzy matcher
            val titles = repo.allTitles()
            assertEquals(1, titles.size)
            assertEquals("Dune", titles.first().cleanTitle)
            assertEquals(2021, titles.first().year)

            // insert review + idempotency
            assertTrue(repo.insertReview(mediaId, "RogerEbert.com", "https://e/dune", now, "Great & epic.", null, 0.92f))
            assertFalse(repo.insertReview(mediaId, "RogerEbert.com", "https://e/dune", now, "dup", null, 0.92f))

            // insert highlight + idempotency
            assertTrue(repo.insertHighlight(mediaId, "festival-win", "Festival Winner", "https://e/cannes", now))
            assertFalse(repo.insertHighlight(mediaId, "festival-win", "Festival Winner", "https://e/cannes", now))

            val bundle = repo.bundleFor(mediaId)
            assertEquals(1, bundle.reviews.size)
            assertEquals(1, bundle.highlights.size)
            assertEquals("RogerEbert.com", bundle.reviews.first().outlet)
            assertEquals("https://e/dune", bundle.reviews.first().url)
            assertEquals("festival-win", bundle.highlights.first().type)
            assertEquals("Festival Winner", bundle.highlights.first().label)
        }
    }
}
