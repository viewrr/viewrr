package wtf.jobin.watch

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID

@Serializable
data class ContinueWatchingItem(
    val mediaId: String,
    val title: String,
    val hlsPath: String?,
    val durationSecs: Int?,
    val positionSecs: Int,
    val percent: Int,
)

/**
 * Continue-watching feed: each media's latest watch_event, excluding media whose
 * latest event is `finish`, newest-resumed first.
 *
 * Exposed v1 has no DISTINCT ON DSL, so this goes through raw SQL on the suspending
 * R2DBC transaction (same shape as [wtf.jobin.media.MediaSearchService]). The inner
 * DISTINCT ON picks the truly-latest row per media (created_at DESC, id DESC tiebreak);
 * the outer query drops finished media, orders by recency, and applies the limit — all
 * in SQL so we never over-fetch. user_id binds as a UUID `?` placeholder, never
 * interpolated.
 */
class ContinueWatchingService(private val db: R2dbcDatabase) {

    suspend fun forUser(userId: UUID, limit: Int): List<ContinueWatchingItem> {
        require(limit > 0) { "limit must be positive" }

        val args: List<Pair<IColumnType<*>, Any?>> = listOf(
            UUIDColumnType() to userId,
            IntegerColumnType() to limit,
        )

        return suspendTransaction(db) {
            exec(CONTINUE_WATCHING_SQL, args) { it.toItem() }
                ?.toList()
                ?.filterNotNull()
                .orEmpty()
        }
    }

    private fun Row.toItem(): ContinueWatchingItem {
        val duration = get("duration_secs", Int::class.javaObjectType)
        val position = get("position_secs", Int::class.javaObjectType)!!
        return ContinueWatchingItem(
            mediaId = get("media_id", UUID::class.java)!!.toString(),
            title = get("title", String::class.java)!!,
            hlsPath = get("hls_path", String::class.java),
            durationSecs = duration,
            positionSecs = position,
            percent = if (duration == null || duration == 0) 0 else position * 100 / duration,
        )
    }

    private companion object {
        private val CONTINUE_WATCHING_SQL = """
            SELECT media_id, title, hls_path, duration_secs, position_secs
            FROM (
                SELECT DISTINCT ON (we.media_id)
                       we.media_id, we.position_secs, we.event_type, we.created_at,
                       mi.title, mi.hls_path, mi.duration_secs
                FROM watch_events we
                JOIN media_items mi ON mi.id = we.media_id
                WHERE we.user_id = ?
                ORDER BY we.media_id, we.created_at DESC, we.id DESC
            ) latest
            WHERE latest.event_type <> 'finish'
            ORDER BY latest.created_at DESC
            LIMIT ?
        """.trimIndent()
    }
}
