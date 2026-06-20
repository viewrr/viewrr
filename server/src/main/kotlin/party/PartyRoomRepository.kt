package wtf.jobin.party

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.PartyMembers
import wtf.jobin.db.PartyRooms
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@Serializable
data class PartyRoomView(
    val id: String,
    val ownerId: String,
    val mediaId: String,
    val joinCode: String,
    val closedAt: String? = null,
)

data class PartyRoomRow(
    val id: UUID,
    val ownerId: UUID,
    val mediaId: UUID,
    val joinCode: String,
    val closedAt: Instant?,
) {
    fun toView() = PartyRoomView(
        id = id.toString(),
        ownerId = ownerId.toString(),
        mediaId = mediaId.toString(),
        joinCode = joinCode,
        closedAt = closedAt?.toString(),
    )
}

class PartyRoomRepository(private val db: R2dbcDatabase) {

    private val rng = SecureRandom()

    suspend fun create(ownerId: UUID, mediaId: UUID): PartyRoomRow = suspendTransaction(db) {
        val now = Instant.now()
        // 36^6 ≈ 2.1B codes — collision is astronomically rare, but retry just in case.
        var lastError: Exception? = null
        repeat(MAX_CODE_ATTEMPTS) {
            val code = randomJoinCode()
            try {
                val newId = PartyRooms.insertAndGetId {
                    it[PartyRooms.ownerId] = ownerId
                    it[PartyRooms.mediaId] = mediaId
                    it[PartyRooms.joinCode] = code
                    it[PartyRooms.lastSyncedAt] = now
                    it[PartyRooms.createdAt] = now
                }
                PartyMembers.insert {
                    it[PartyMembers.roomId] = newId.value
                    it[PartyMembers.userId] = ownerId
                    it[PartyMembers.joinedAt] = now
                }
                return@suspendTransaction PartyRoomRow(
                    id = newId.value,
                    ownerId = ownerId,
                    mediaId = mediaId,
                    joinCode = code,
                    closedAt = null,
                )
            } catch (e: Exception) {
                if (!isUniqueViolation(e)) throw e
                lastError = e
            }
        }
        error("could not allocate unique party_rooms.join_code after $MAX_CODE_ATTEMPTS attempts: ${lastError?.message}")
    }

    suspend fun findOpenByJoinCode(code: String): PartyRoomRow? = suspendTransaction(db) {
        PartyRooms.selectAll()
            .where { (PartyRooms.joinCode eq code) and (PartyRooms.closedAt.isNull()) }
            .map { it.toRow() }
            .firstOrNull()
    }

    suspend fun findById(id: UUID): PartyRoomRow? = suspendTransaction(db) {
        PartyRooms.selectAll()
            .where { PartyRooms.id eq id }
            .map { it.toRow() }
            .firstOrNull()
    }

    /** Idempotent: re-joining a room clears left_at and preserves the original joined_at. */
    suspend fun join(roomId: UUID, userId: UUID): Unit = suspendTransaction(db) {
        val now = Instant.now()
        PartyMembers.upsert(
            // No keys → primary key (roomId, userId) is used for conflict detection.
            onUpdate = { it[PartyMembers.leftAt] = null },
        ) {
            it[PartyMembers.roomId] = roomId
            it[PartyMembers.userId] = userId
            it[PartyMembers.joinedAt] = now
        }
        Unit
    }

    /** No-op if the (room, user) pair never existed or is already left. */
    suspend fun leave(roomId: UUID, userId: UUID): Unit = suspendTransaction(db) {
        PartyMembers.update({ (PartyMembers.roomId eq roomId) and (PartyMembers.userId eq userId) }) {
            it[PartyMembers.leftAt] = Instant.now()
        }
        Unit
    }

    /** Returns the updated row, or null if the room doesn't exist. */
    suspend fun close(roomId: UUID): PartyRoomRow? = suspendTransaction(db) {
        val updated = PartyRooms.update({ PartyRooms.id eq roomId }) {
            it[PartyRooms.closedAt] = Instant.now()
        }
        if (updated == 0) {
            null
        } else {
            PartyRooms.selectAll()
                .where { PartyRooms.id eq roomId }
                .map { it.toRow() }
                .firstOrNull()
        }
    }

    private fun randomJoinCode(): String = buildString(JOIN_CODE_LEN) {
        repeat(JOIN_CODE_LEN) { append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
    }

    private fun ResultRow.toRow() = PartyRoomRow(
        id = this[PartyRooms.id].value,
        ownerId = this[PartyRooms.ownerId].value,
        mediaId = this[PartyRooms.mediaId].value,
        joinCode = this[PartyRooms.joinCode],
        closedAt = this[PartyRooms.closedAt],
    )

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = (cur.message ?: "").lowercase()
            if ("23505" in msg || "duplicate key" in msg || "unique constraint" in msg) return true
            cur = cur.cause
        }
        return false
    }

    private companion object {
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        const val JOIN_CODE_LEN = 6
        const val MAX_CODE_ATTEMPTS = 5
    }
}
