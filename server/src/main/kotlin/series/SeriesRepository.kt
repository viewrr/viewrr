package wtf.jobin.series

import kotlinx.coroutines.flow.map
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
    suspend fun shows(): List<ShowSummary> = suspendTransaction(db) {
        MediaItems
            .select(MediaItems.showTitle, MediaItems.seasonNumber)
            .where { MediaItems.showTitle.isNotNull() }
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

    suspend fun episodes(showTitle: String): List<EpisodeRow> = suspendTransaction(db) {
        MediaItems
            .select(
                MediaItems.id,
                MediaItems.cleanTitle,
                MediaItems.title,
                MediaItems.seasonNumber,
                MediaItems.episodeNumber,
                MediaItems.hlsPath,
            )
            .where { MediaItems.showTitle eq showTitle }
            .orderBy(
                MediaItems.seasonNumber to SortOrder.ASC,
                MediaItems.episodeNumber to SortOrder.ASC,
            )
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
