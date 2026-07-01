package wtf.jobin.identity

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.IdentityAccounts
import java.time.Instant
import java.util.UUID

data class IdentityAccountRow(val id: UUID, val publicKey: String, val displayName: String? = null)

open class IdentityAccountRepository(private val db: R2dbcDatabase) {
    open suspend fun findByPublicKey(publicKeyHex: String): IdentityAccountRow? = suspendTransaction(db) {
        IdentityAccounts.selectAll()
            .where { IdentityAccounts.publicKey eq publicKeyHex }
            .map { it.toRow() }
            .firstOrNull()
    }

    open suspend fun create(publicKeyHex: String, displayName: String? = null): IdentityAccountRow = suspendTransaction(db) {
        val id = IdentityAccounts.insertAndGetId {
            it[IdentityAccounts.publicKey] = publicKeyHex
            it[IdentityAccounts.displayName] = displayName
            it[IdentityAccounts.createdAt] = Instant.now()
        }
        IdentityAccountRow(id.value, publicKeyHex, displayName)
    }

    private fun ResultRow.toRow() = IdentityAccountRow(
        id = this[IdentityAccounts.id].value,
        publicKey = this[IdentityAccounts.publicKey],
        displayName = this[IdentityAccounts.displayName],
    )
}
