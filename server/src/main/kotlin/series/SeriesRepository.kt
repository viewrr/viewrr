package wtf.jobin.series

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import java.util.UUID

data class ShowSummary(val showTitle: String, val episodeCount: Int, val seasonCount: Int)

data class EpisodeRow(
    val mediaId: UUID,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val hlsPath: String?,
)

/**
 * Read-only browse over media_items rows that carry a show_title (i.e. episodes).
 * Ktor never writes here; the scanner owns the show/season/episode metadata.
 */
class SeriesRepository(private val db: R2dbcDatabase) {

    // ponytail: in-memory grouping (library is small; switch to SQL GROUP BY if it ever isn't).
    suspend fun shows(maxRating: String?): List<ShowSummary> = suspendTransaction(db) {
        MediaItems
            .select(MediaItems.showTitle, MediaItems.seasonNumber, MediaItems.contentRating)
            .where { MediaItems.showTitle.isNotNull() }
            .filter { wtf.jobin.rating.isVisible(maxRating, it[MediaItems.contentRating]) }
            .map { it[MediaItems.showTitle]!! to it[MediaItems.seasonNumber] }
            .toList()
            .groupBy({ it.first }, { it.second })
            .map { (showTitle, seasons) ->
                ShowSummary(
                    showTitle = showTitle,
                    episodeCount = seasons.size,
                    seasonCount = seasons.filterNotNull().distinct().size,
                )
            }
            .sortedBy { it.showTitle }
    }

    suspend fun episodes(showTitle: String, maxRating: String?): List<EpisodeRow> = suspendTransaction(db) {
        MediaItems
            .select(
                MediaItems.id,
                MediaItems.cleanTitle,
                MediaItems.title,
                MediaItems.seasonNumber,
                MediaItems.episodeNumber,
                MediaItems.hlsPath,
                MediaItems.contentRating,
            )
            .where { MediaItems.showTitle eq showTitle }
            .orderBy(
                MediaItems.seasonNumber to SortOrder.ASC,
                MediaItems.episodeNumber to SortOrder.ASC,
            )
            .filter { wtf.jobin.rating.isVisible(maxRating, it[MediaItems.contentRating]) }
            .map {
                EpisodeRow(
                    mediaId = it[MediaItems.id].value,
                    title = it[MediaItems.cleanTitle] ?: it[MediaItems.title],
                    seasonNumber = it[MediaItems.seasonNumber],
                    episodeNumber = it[MediaItems.episodeNumber],
                    hlsPath = it[MediaItems.hlsPath],
                )
            }
            .toList()
    }
}
