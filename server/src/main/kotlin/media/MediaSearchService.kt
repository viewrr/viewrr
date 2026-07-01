package wtf.jobin.media

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID
import wtf.jobin.rating.isVisible
import wtf.jobin.rating.maxRatingFor

@Serializable
data class MediaSearchHit(
    val id: String,
    val title: String,
    val hlsPath: String?,
    val durationSecs: Int?,
    val mimeType: String?,
    val contentRating: String?,
)

/**
 * BM25 search over media_items.title using the ParadeDB pg_search index built
 * in V2__paradedb_bm25.sql. Goes through raw SQL on the suspending R2DBC
 * transaction because Exposed v1 has no first-class @@@ DSL.
 *
 * The `@@@` operator parses its RHS as a Tantivy query string (pg_search 0.20+),
 * so callers can use `+matrix -resurrections` style refinements. The bound `?`
 * placeholder keeps it parameterised — no SQL injection vector.
 */
class MediaSearchService(private val db: R2dbcDatabase) {

    suspend fun search(query: String, limit: Int, userId: UUID): List<MediaSearchHit> {
        require(query.isNotBlank()) { "q must not be blank" }
        require(limit > 0) { "limit must be positive" }
        // ponytail: no upper bound on limit; add a cap when search shows abuse.

        val args: List<Pair<IColumnType<*>, Any?>> = listOf(
            TextColumnType() to query,
            IntegerColumnType() to limit,
        )

        val hits = suspendTransaction(db) {
            exec(SEARCH_SQL, args) { it.toHit() }
                ?.toList()
                ?.filterNotNull()
                .orEmpty()
        }
        // ponytail: filter in Kotlin over the limit-bounded result set.
        val max = maxRatingFor(db, userId)
        return hits.filter { isVisible(max, it.contentRating) }
    }

    private fun Row.toHit() = MediaSearchHit(
        id = get("id", UUID::class.java)!!.toString(),
        title = get("title", String::class.java)!!,
        hlsPath = get("hls_path", String::class.java),
        durationSecs = get("duration_secs", Int::class.javaObjectType),
        mimeType = get("mime_type", String::class.java),
        contentRating = get("content_rating", String::class.java),
    )

    private companion object {
        // #128: mirrors publicCatalogOp() — exclude de-indexed + non-TMDB Titles
        // from search results (allowlist gate). Keep in sync with MediaModeration.
        private val SEARCH_SQL = """
            SELECT id, title, hls_path, duration_secs, mime_type, content_rating, paradedb.score(id) AS score
            FROM media_items
            WHERE title @@@ ?
              AND deindexed = false
              AND tmdb_id IS NOT NULL
            ORDER BY score DESC
            LIMIT ?
        """.trimIndent()
    }
}
