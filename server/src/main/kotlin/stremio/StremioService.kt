package wtf.jobin.stremio

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MediaItems
import wtf.jobin.rating.isVisible
import wtf.jobin.rating.maxRatingFor
import java.util.Base64
import java.util.UUID

/**
 * Maps viewrr's library into the Stremio addon content model, parental-filtered by
 * the resolved user's max_rating.
 *
 * IDs: movies = `viewrr:movie:<uuid>`, shows = `viewrr:show:<b64url(title)>`,
 * episodes = `viewrr:show:<b64url(title)>:<season>:<episode>`.
 */
class StremioService(private val db: R2dbcDatabase, private val publicBaseUrl: String) {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()

    private fun encShow(title: String) = b64.encodeToString(title.toByteArray())
    private fun decShow(s: String) = String(b64d.decode(s))

    fun manifest(): StManifest = StManifest(
        id = "wtf.jobin.viewrr",
        version = "1.0.0",
        name = "viewrr",
        description = "Your viewrr library - movies and shows.",
        resources = listOf("catalog", "meta", "stream", "subtitles"),
        types = listOf("movie", "series"),
        catalogs = listOf(
            StCatalog("movie", "viewrr-movies", "viewrr Movies", listOf(StExtra("search"))),
            StCatalog("series", "viewrr-series", "viewrr Shows", listOf(StExtra("search"))),
        ),
        idPrefixes = listOf("viewrr:"),
    )

    private data class Row(
        val id: UUID, val title: String, val clean: String?, val show: String?,
        val season: Int?, val episode: Int?, val year: Int?, val duration: Int?, val rating: String?,
    )

    private fun ResultRow.toRow() = Row(
        this[MediaItems.id].value, this[MediaItems.title], this[MediaItems.cleanTitle],
        this[MediaItems.showTitle], this[MediaItems.seasonNumber], this[MediaItems.episodeNumber],
        this[MediaItems.year]?.toInt(), this[MediaItems.durationSecs], this[MediaItems.contentRating],
    )

    suspend fun movieCatalog(userId: UUID, search: String?): List<StMetaPreview> {
        val max = maxRatingFor(db, userId)
        val rows = suspendTransaction(db) {
            MediaItems.selectAll().where { MediaItems.showTitle.isNull() }.map { it.toRow() }.toList()
        }
        return rows
            .filter { isVisible(max, it.rating) }
            .filter { search == null || (it.clean ?: it.title).contains(search, ignoreCase = true) }
            .sortedBy { (it.clean ?: it.title).lowercase() }
            .map { StMetaPreview("viewrr:movie:${it.id}", "movie", it.clean ?: it.title, releaseInfo = it.year?.toString()) }
    }

    suspend fun seriesCatalog(userId: UUID, search: String?): List<StMetaPreview> {
        val max = maxRatingFor(db, userId)
        val rows = suspendTransaction(db) {
            MediaItems.selectAll().where { MediaItems.showTitle.isNotNull() }.map { it.toRow() }.toList()
        }
        return rows
            .filter { isVisible(max, it.rating) }
            .groupBy { it.show!! }
            .filter { (show, _) -> search == null || show.contains(search, ignoreCase = true) }
            .toSortedMap()
            .map { (show, _) -> StMetaPreview("viewrr:show:${encShow(show)}", "series", show) }
    }

    suspend fun meta(userId: UUID, type: String, id: String): StMeta? {
        val max = maxRatingFor(db, userId)
        return when {
            type == "movie" && id.startsWith("viewrr:movie:") -> {
                val uuid = runCatching { UUID.fromString(id.removePrefix("viewrr:movie:")) }.getOrNull() ?: return null
                val r = suspendTransaction(db) {
                    MediaItems.selectAll().where { MediaItems.id eq uuid }.map { it.toRow() }.firstOrNull()
                }?.takeIf { isVisible(max, it.rating) } ?: return null
                StMeta(id, "movie", r.clean ?: r.title, releaseInfo = r.year?.toString(), runtime = r.duration?.let { "${it / 60}m" })
            }
            type == "series" && id.startsWith("viewrr:show:") -> {
                val show = runCatching { decShow(id.removePrefix("viewrr:show:")) }.getOrNull() ?: return null
                val eps = suspendTransaction(db) {
                    MediaItems.selectAll().where { MediaItems.showTitle eq show }.map { it.toRow() }.toList()
                }.filter { isVisible(max, it.rating) }
                if (eps.isEmpty()) return null
                val videos = eps.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 })).map {
                    StVideo(
                        id = "$id:${it.season ?: 1}:${it.episode ?: 1}",
                        title = it.clean ?: it.title,
                        season = it.season, episode = it.episode,
                    )
                }
                StMeta(id, "series", show, videos = videos)
            }
            else -> null
        }
    }

    /** Resolve a Stremio stream/meta id to a viewrr media UUID (parental-checked). */
    private suspend fun resolveStreamId(userId: UUID, id: String): UUID? {
        val max = maxRatingFor(db, userId)
        return when {
            id.startsWith("viewrr:movie:") -> {
                val uuid = runCatching { UUID.fromString(id.removePrefix("viewrr:movie:")) }.getOrNull() ?: return null
                suspendTransaction(db) {
                    MediaItems.selectAll().where { MediaItems.id eq uuid }.map { it.toRow() }.firstOrNull()
                }?.takeIf { isVisible(max, it.rating) }?.id
            }
            id.startsWith("viewrr:show:") -> {
                val parts = id.removePrefix("viewrr:show:").split(":")
                if (parts.size < 3) return null
                val show = runCatching { decShow(parts[0]) }.getOrNull() ?: return null
                val s = parts[parts.size - 2].toIntOrNull()
                val e = parts[parts.size - 1].toIntOrNull()
                suspendTransaction(db) {
                    MediaItems.selectAll()
                        .where { (MediaItems.showTitle eq show) and (MediaItems.seasonNumber eq s) and (MediaItems.episodeNumber eq e) }
                        .map { it.toRow() }.firstOrNull()
                }?.takeIf { isVisible(max, it.rating) }?.id
            }
            else -> null
        }
    }

    suspend fun streams(userId: UUID, key: String, id: String): List<StStream> {
        val uuid = resolveStreamId(userId, id) ?: return emptyList()
        return listOf(StStream(url = "$publicBaseUrl/stream/$uuid/playlist.m3u8?key=$key", name = "viewrr"))
    }

    suspend fun mediaIdFor(userId: UUID, id: String): UUID? = resolveStreamId(userId, id)
}
