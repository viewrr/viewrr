package wtf.jobin.music

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.MusicTracks
import java.util.UUID

data class AlbumSummary(val album: String, val trackCount: Int, val artist: String?)

data class TrackRow(
    val id: UUID,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationSecs: Int?,
    val originalPath: String,
    val mimeType: String?,
    val libraryId: UUID,
)

class MusicRepository(private val db: R2dbcDatabase) {

    /** Albums grouped in Kotlin; artist = most-common album_artist, then artist. Untitled-album tracks excluded. */
    suspend fun albums(): List<AlbumSummary> {
        val rows = suspendTransaction(db) {
            MusicTracks.selectAll().map { it.toRow() }.toList()
        }
        return rows.filter { it.album != null }
            .groupBy { it.album!! }
            .map { (album, tracks) ->
                val artist = (tracks.mapNotNull { it.albumArtist } + tracks.mapNotNull { it.artist })
                    .groupingBy { it }.eachCount()
                    .maxByOrNull { it.value }?.key
                AlbumSummary(album, tracks.size, artist)
            }
            .sortedBy { it.album.lowercase() }
    }

    suspend fun tracksByAlbum(album: String): List<TrackRow> {
        val rows = suspendTransaction(db) {
            MusicTracks.selectAll()
                .where { MusicTracks.album eq album }
                .map { it.toRow() }
                .toList()
        }
        return rows.sortedWith(
            compareBy({ it.discNumber ?: 0 }, { it.trackNumber ?: 0 }, { it.title.lowercase() }),
        )
    }

    suspend fun track(id: UUID): TrackRow? = suspendTransaction(db) {
        MusicTracks.selectAll()
            .where { MusicTracks.id eq id }
            .map { it.toRow() }
            .firstOrNull()
    }

    private fun ResultRow.toRow() = TrackRow(
        id = this[MusicTracks.id].value,
        title = this[MusicTracks.title],
        artist = this[MusicTracks.artist],
        album = this[MusicTracks.album],
        albumArtist = this[MusicTracks.albumArtist],
        trackNumber = this[MusicTracks.trackNumber],
        discNumber = this[MusicTracks.discNumber],
        durationSecs = this[MusicTracks.durationSecs],
        originalPath = this[MusicTracks.originalPath],
        mimeType = this[MusicTracks.mimeType],
        libraryId = this[MusicTracks.libraryId].value,
    )
}
