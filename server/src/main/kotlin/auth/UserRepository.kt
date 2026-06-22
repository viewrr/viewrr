package wtf.jobin.auth

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Users
import java.time.Instant
import java.util.UUID

data class UserRow(
    val id: UUID,
    val username: String,
    val email: String,
    val passwordHash: String,
    val displayName: String?,
    val isAdmin: Boolean,
    val isActive: Boolean,
)

open class UserRepository(private val db: R2dbcDatabase) {
    open suspend fun create(
        username: String,
        email: String,
        passwordHash: String,
        displayName: String?,
    ): UserRow = suspendTransaction(db) {
        val now = Instant.now()
        val id = Users.insertAndGetId {
            it[Users.username] = username
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.displayName] = displayName
            it[Users.createdAt] = now
            it[Users.updatedAt] = now
        }
        UserRow(id.value, username, email, passwordHash, displayName, false, true)
    }

    open suspend fun findByUsername(username: String): UserRow? = suspendTransaction(db) {
        Users.selectAll()
            .where { Users.username.lowerCase() eq username.lowercase() }
            .map { it.toRow() }
            .firstOrNull()
    }

    suspend fun findById(id: UUID): UserRow? = suspendTransaction(db) {
        Users.selectAll()
            .where { Users.id eq id }
            .map { it.toRow() }
            .firstOrNull()
    }

    suspend fun setAdmin(id: UUID, isAdmin: Boolean): UserRow? = suspendTransaction(db) {
        val updated = Users.update({ Users.id eq id }) {
            it[Users.isAdmin] = isAdmin
            it[Users.updatedAt] = Instant.now()
        }
        if (updated == 0) {
            null
        } else {
            Users.selectAll()
                .where { Users.id eq id }
                .map { it.toRow() }
                .firstOrNull()
        }
    }

    open suspend fun list(): List<UserRow> = suspendTransaction(db) {
        Users.selectAll()
            .orderBy(Users.createdAt to SortOrder.ASC)
            .map { it.toRow() }
            .toList()
    }

    suspend fun setActive(id: UUID, active: Boolean): UserRow? = suspendTransaction(db) {
        val updated = Users.update({ Users.id eq id }) {
            it[Users.isActive] = active
            it[Users.updatedAt] = Instant.now()
        }
        if (updated == 0) {
            null
        } else {
            Users.selectAll()
                .where { Users.id eq id }
                .map { it.toRow() }
                .firstOrNull()
        }
    }

    suspend fun delete(id: UUID): Boolean = suspendTransaction(db) {
        Users.deleteWhere { Users.id eq id } > 0
    }

    private fun ResultRow.toRow() = UserRow(
        id = this[Users.id].value,
        username = this[Users.username],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        displayName = this[Users.displayName],
        isAdmin = this[Users.isAdmin],
        isActive = this[Users.isActive],
    )
}
