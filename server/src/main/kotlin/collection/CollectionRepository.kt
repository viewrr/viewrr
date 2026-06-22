package wtf.jobin.collection

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.CollectionItems
import wtf.jobin.db.Collections
import wtf.jobin.db.MediaItems
import java.time.Instant
import java.util.UUID

data class CollectionRow(val id: UUID, val name: String, val createdAt: Instant)

data class CollectionItemRow(val mediaId: UUID, val title: String, val hlsPath: String?, val position: Int)

/**
 * Every read and mutation is owner-scoped: the ownerId predicate is part of the
 * WHERE clause, so a caller can never see or touch another user's collection.
 */
class CollectionRepository(private val db: R2dbcDatabase) {

    suspend fun create(ownerId: UUID, name: String): CollectionRow = suspendTransaction(db) {
        val now = Instant.now()
        val newId = Collections.insertAndGetId {
            it[Collections.ownerId] = ownerId
            it[Collections.name] = name
            it[Collections.createdAt] = now
        }
        CollectionRow(newId.value, name, now)
    }

    suspend fun listByOwner(ownerId: UUID): List<CollectionRow> = suspendTransaction(db) {
        Collections.selectAll()
            .where { Collections.ownerId eq ownerId }
            .orderBy(Collections.createdAt to SortOrder.ASC)
            .map {
                CollectionRow(it[Collections.id].value, it[Collections.name], it[Collections.createdAt])
            }
            .toList()
    }

    suspend fun get(ownerId: UUID, id: UUID): CollectionRow? = suspendTransaction(db) {
        Collections.selectAll()
            .where { (Collections.id eq id) and (Collections.ownerId eq ownerId) }
            .map {
                CollectionRow(it[Collections.id].value, it[Collections.name], it[Collections.createdAt])
            }
            .firstOrNull()
    }

    suspend fun delete(ownerId: UUID, id: UUID): Boolean = suspendTransaction(db) {
        Collections.deleteWhere { (Collections.id eq id) and (Collections.ownerId eq ownerId) } > 0
    }

    suspend fun items(ownerId: UUID, id: UUID): List<CollectionItemRow>? {
        // Ownership gate first — a non-owner gets null, never a leaked item list.
        if (get(ownerId, id) == null) return null
        return suspendTransaction(db) {
            CollectionItems.join(MediaItems, JoinType.INNER, CollectionItems.mediaId, MediaItems.id)
                .select(MediaItems.id, MediaItems.title, MediaItems.hlsPath, CollectionItems.position)
                .where { CollectionItems.collectionId eq id }
                .orderBy(CollectionItems.position to SortOrder.ASC)
                .map {
                    CollectionItemRow(
                        mediaId = it[MediaItems.id].value,
                        title = it[MediaItems.title],
                        hlsPath = it[MediaItems.hlsPath],
                        position = it[CollectionItems.position],
                    )
                }
                .toList()
        }
    }

    suspend fun addItem(ownerId: UUID, id: UUID, mediaId: UUID, position: Int): Boolean {
        if (get(ownerId, id) == null) return false
        suspendTransaction(db) {
            // ponytail: delete-then-insert keeps add idempotent (re-adding updates position).
            CollectionItems.deleteWhere {
                (CollectionItems.collectionId eq id) and (CollectionItems.mediaId eq mediaId)
            }
            CollectionItems.insert {
                it[CollectionItems.collectionId] = id
                it[CollectionItems.mediaId] = mediaId
                it[CollectionItems.position] = position
                it[CollectionItems.addedAt] = Instant.now()
            }
        }
        return true
    }

    suspend fun removeItem(ownerId: UUID, id: UUID, mediaId: UUID): Boolean {
        if (get(ownerId, id) == null) return false
        return suspendTransaction(db) {
            CollectionItems.deleteWhere {
                (CollectionItems.collectionId eq id) and (CollectionItems.mediaId eq mediaId)
            } > 0
        }
    }
}
