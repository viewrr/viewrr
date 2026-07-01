package wtf.jobin.editorial

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.db.MovieHighlights
import wtf.jobin.db.MovieReviews
import java.time.Instant
import java.util.UUID

@Serializable
data class ReviewDto(
    val outlet: String,
    val url: String,
    val publishedAt: String? = null, // ISO-8601; client formats
    val snippet: String? = null,
    val parsedRating: Float? = null,
    val matchScore: Float? = null,
)

@Serializable
data class HighlightDto(
    val type: String,
    val label: String,
    val sourceUrl: String? = null,
    val date: String? = null,
)

/** Combined payload for GET /media/{id}/reviews. */
@Serializable
data class EditorialBundle(
    val reviews: List<ReviewDto>,
    val highlights: List<HighlightDto>,
)

/**
 * Storage + read for editorial reviews/highlights. Inserts are idempotent at the app level
 * (existence check before insert) so re-running a refresh never duplicates a link — the DB unique
 * indexes are the backstop.
 */
class EditorialRepository(private val db: R2dbcDatabase) {

    /** Every catalog Title, for the fuzzy matcher. ponytail: fine for personal libraries; page/stream if the catalog grows huge. */
    suspend fun allTitles(): List<MovieTitle> = suspendTransaction(db) {
        MediaItems
            .select(MediaItems.id, MediaItems.title, MediaItems.cleanTitle, MediaItems.year)
            .map {
                MovieTitle(
                    id = it[MediaItems.id].value,
                    title = it[MediaItems.title],
                    cleanTitle = it[MediaItems.cleanTitle],
                    year = it[MediaItems.year]?.toInt(),
                )
            }
            .toList()
    }

    /** @return true if a new row was written, false if it already existed. */
    suspend fun insertReview(
        mediaItemId: UUID,
        outlet: String,
        url: String,
        publishedAt: Instant?,
        snippet: String?,
        parsedRating: Float?,
        matchScore: Float?,
    ): Boolean = suspendTransaction(db) {
        val exists = MovieReviews
            .select(MovieReviews.id)
            .where { (MovieReviews.mediaItemId eq mediaItemId) and (MovieReviews.url eq url) }
            .map { true }.toList().isNotEmpty()
        if (exists) return@suspendTransaction false
        MovieReviews.insert {
            it[MovieReviews.mediaItemId] = mediaItemId
            it[MovieReviews.outlet] = outlet
            it[MovieReviews.url] = url
            it[MovieReviews.publishedAt] = publishedAt
            it[MovieReviews.snippet] = snippet
            it[MovieReviews.parsedRating] = parsedRating
            it[MovieReviews.matchScore] = matchScore
            it[MovieReviews.createdAt] = Instant.now()
        }
        true
    }

    /** @return true if a new badge was written, false if it already existed. */
    suspend fun insertHighlight(
        mediaItemId: UUID,
        type: String,
        label: String,
        sourceUrl: String?,
        date: Instant?,
    ): Boolean = suspendTransaction(db) {
        val exists = MovieHighlights
            .select(MovieHighlights.id)
            .where {
                (MovieHighlights.mediaItemId eq mediaItemId) and
                    (MovieHighlights.type eq type) and
                    (MovieHighlights.label eq label)
            }
            .map { true }.toList().isNotEmpty()
        if (exists) return@suspendTransaction false
        MovieHighlights.insert {
            it[MovieHighlights.mediaItemId] = mediaItemId
            it[MovieHighlights.type] = type
            it[MovieHighlights.label] = label
            it[MovieHighlights.sourceUrl] = sourceUrl
            it[MovieHighlights.date] = date
            it[MovieHighlights.createdAt] = Instant.now()
        }
        true
    }

    suspend fun bundleFor(mediaItemId: UUID): EditorialBundle = suspendTransaction(db) {
        val reviews = MovieReviews.selectAll()
            .where { MovieReviews.mediaItemId eq mediaItemId }
            .orderBy(MovieReviews.publishedAt, SortOrder.DESC_NULLS_LAST)
            .map {
                ReviewDto(
                    outlet = it[MovieReviews.outlet],
                    url = it[MovieReviews.url],
                    publishedAt = it[MovieReviews.publishedAt]?.toString(),
                    snippet = it[MovieReviews.snippet],
                    parsedRating = it[MovieReviews.parsedRating],
                    matchScore = it[MovieReviews.matchScore],
                )
            }
            .toList()
        val highlights = MovieHighlights.selectAll()
            .where { MovieHighlights.mediaItemId eq mediaItemId }
            .orderBy(MovieHighlights.date, SortOrder.DESC_NULLS_LAST)
            .map {
                HighlightDto(
                    type = it[MovieHighlights.type],
                    label = it[MovieHighlights.label],
                    sourceUrl = it[MovieHighlights.sourceUrl],
                    date = it[MovieHighlights.date]?.toString(),
                )
            }
            .toList()
        EditorialBundle(reviews, highlights)
    }
}
