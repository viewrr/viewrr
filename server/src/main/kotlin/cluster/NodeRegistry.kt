package wtf.jobin.cluster

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.Nodes
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Phase 14 (#69 register, #73 auth). Hub-side node enrollment + token verification.
 *
 * Register: an Agent presents the shared enrollment secret and gets a fresh node row
 * plus a one-time per-node token. Only the token's sha256 hash is persisted; the raw
 * token guards subsequent Hub<->Agent calls (plaintext on LAN — see ADR / #73).
 *
 * ponytail: every register() mints a new node. Re-register/refresh-by-id and heartbeat
 * are separate issues (#83); not needed until the Agent binary calls this.
 */
class NodeRegistry(
    private val db: R2dbcDatabase,
    private val enrollmentSecret: String,
) {
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    class BadEnrollment : RuntimeException("invalid enrollment secret")

    data class Registered(val nodeId: UUID, val token: String)

    suspend fun register(
        secret: String,
        name: String,
        meshAddress: String?,
        clientAddress: String?,
    ): Registered {
        if (secret != enrollmentSecret) throw BadEnrollment()
        val token = b64.encodeToString(ByteArray(32).also(rng::nextBytes))
        val id = suspendTransaction(db) {
            Nodes.insertAndGetId {
                it[Nodes.name] = name
                it[Nodes.meshAddress] = meshAddress
                it[Nodes.clientAddress] = clientAddress
                it[Nodes.tokenHash] = sha256(token)
                it[Nodes.createdAt] = Instant.now()
            }.value
        }
        return Registered(id, token)
    }

    /** Resolve a presented token to its node id, or null if unknown. Used to authenticate Agent calls. */
    suspend fun resolve(token: String): UUID? {
        val h = sha256(token)
        return suspendTransaction(db) {
            Nodes.select(Nodes.id).where { Nodes.tokenHash eq h }
                .map { it[Nodes.id].value }
                .firstOrNull()
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
