package wtf.jobin.auth

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
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
)

class UserRepository(private val db: R2dbcDatabase) {
    suspend fun create(
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
        UserRow(id.value, username, email, passwordHash, displayName, false)
    }

    suspend fun findByUsername(username: String): UserRow? = suspendTransaction(db) {
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

    private fun ResultRow.toRow() = UserRow(
        id = this[Users.id].value,
        username = this[Users.username],
        email = this[Users.email],
        passwordHash = this[Users.passwordHash],
        displayName = this[Users.displayName],
        isAdmin = this[Users.isAdmin],
    )
}
