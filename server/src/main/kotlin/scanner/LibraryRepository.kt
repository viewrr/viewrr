package wtf.jobin.scanner

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Libraries
import java.time.Instant
import java.util.UUID

@Serializable
data class LibraryView(
    val id: String,
    val name: String,
    val kind: String,
    val rootPath: String,
    val watchEnabled: Boolean,
    val lastScannedAt: String? = null,
    val createdAt: String,
)

data class LibraryRow(
    val id: UUID,
    val name: String,
    val kind: String,
    val rootPath: String,
    val watchEnabled: Boolean,
    val lastScannedAt: Instant?,
    val createdAt: Instant,
) {
    fun toView() = LibraryView(
        id = id.toString(),
        name = name,
        kind = kind,
        rootPath = rootPath,
        watchEnabled = watchEnabled,
        lastScannedAt = lastScannedAt?.toString(),
        createdAt = createdAt.toString(),
    )
}

class LibraryRepository(private val db: R2dbcDatabase) {

    suspend fun create(name: String, kind: String, rootPath: String): LibraryRow = suspendTransaction(db) {
        val now = Instant.now()
        val newId = Libraries.insertAndGetId {
            it[Libraries.name] = name
            it[Libraries.kind] = kind
            it[Libraries.rootPath] = rootPath
            it[Libraries.watchEnabled] = true
            it[Libraries.createdAt] = now
        }
        LibraryRow(
            id = newId.value,
            name = name,
            kind = kind,
            rootPath = rootPath,
            watchEnabled = true,
            lastScannedAt = null,
            createdAt = now,
        )
    }

    suspend fun list(): List<LibraryRow> = suspendTransaction(db) {
        Libraries.selectAll()
            .orderBy(Libraries.createdAt to SortOrder.ASC)
            .map { it.toRow() }
            .toList()
    }

    suspend fun findById(id: UUID): LibraryRow? = suspendTransaction(db) {
        Libraries.selectAll()
            .where { Libraries.id eq id }
            .map { it.toRow() }
            .firstOrNull()
    }

    /** Returns the updated row, or null if `id` doesn't exist. No-op patches still return the current row. */
    suspend fun patch(id: UUID, name: String?, watchEnabled: Boolean?): LibraryRow? = suspendTransaction(db) {
        if (name != null || watchEnabled != null) {
            val updated = Libraries.update({ Libraries.id eq id }) {
                if (name != null) it[Libraries.name] = name
                if (watchEnabled != null) it[Libraries.watchEnabled] = watchEnabled
            }
            if (updated == 0) return@suspendTransaction null
        }
        Libraries.selectAll()
            .where { Libraries.id eq id }
            .map { it.toRow() }
            .firstOrNull()
    }

    /** Returns true if a row was removed. media_items cascade via FK ON DELETE CASCADE. */
    suspend fun delete(id: UUID): Boolean = suspendTransaction(db) {
        Libraries.deleteWhere { Libraries.id eq id } > 0
    }

    private fun ResultRow.toRow() = LibraryRow(
        id = this[Libraries.id].value,
        name = this[Libraries.name],
        kind = this[Libraries.kind],
        rootPath = this[Libraries.rootPath],
        watchEnabled = this[Libraries.watchEnabled],
        lastScannedAt = this[Libraries.lastScannedAt],
        createdAt = this[Libraries.createdAt],
    )
}
