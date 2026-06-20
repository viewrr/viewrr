package wtf.jobin.watch

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.WatchEvents
import java.time.Instant
import java.util.UUID

data class WatchEventRow(
    val id: Long,
    val userId: UUID,
    val mediaId: UUID,
    val positionSecs: Int,
    val eventType: String,
    val sessionId: UUID,
    val createdAt: Instant,
)

class WatchEventRepository(private val db: R2dbcDatabase) {

    suspend fun insert(
        userId: UUID,
        mediaId: UUID,
        positionSecs: Int,
        eventType: String,
        sessionId: UUID,
    ): Long = suspendTransaction(db) {
        val stmt = WatchEvents.insert {
            it[WatchEvents.userId] = userId
            it[WatchEvents.mediaId] = mediaId
            it[WatchEvents.positionSecs] = positionSecs
            it[WatchEvents.eventType] = eventType
            it[WatchEvents.sessionId] = sessionId
            it[WatchEvents.createdAt] = Instant.now()
        }
        stmt[WatchEvents.id]
    }

    suspend fun findForUserAndMedia(
        userId: UUID,
        mediaId: UUID,
        limit: Int,
    ): List<WatchEventRow> = suspendTransaction(db) {
        WatchEvents.selectAll()
            .where { (WatchEvents.userId eq userId) and (WatchEvents.mediaId eq mediaId) }
            .orderBy(WatchEvents.createdAt, SortOrder.DESC)
            .orderBy(WatchEvents.id, SortOrder.DESC)
            .limit(limit)
            .map { it.toRow() }
            .toList()
    }

    private fun ResultRow.toRow() = WatchEventRow(
        id = this[WatchEvents.id],
        userId = this[WatchEvents.userId].value,
        mediaId = this[WatchEvents.mediaId].value,
        positionSecs = this[WatchEvents.positionSecs],
        eventType = this[WatchEvents.eventType],
        sessionId = this[WatchEvents.sessionId],
        createdAt = this[WatchEvents.createdAt],
    )
}
