package wtf.jobin.pay

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import wtf.jobin.db.PayWallets
import java.time.Instant
import java.util.UUID

/**
 * mesh-hub (docs/pay/1-grpc-contract-service-skeleton.md): Hub-local record of the wallet opt-in
 * gate backing `WalletRoutes`. A row for an account exists ONLY after
 * [SettlementClient.ensureWallet] has actually returned an address — this is the single choke
 * point `GET /api/pay/wallet` consults to decide whether to call the gRPC service at all.
 */
class PayWalletRepository(private val db: R2dbcDatabase) {

    /** The account's cached wallet address, or null if it never opted in. */
    suspend fun findAddress(accountId: UUID): String? = suspendTransaction(db) {
        PayWallets.selectAll()
            .where { PayWallets.accountId eq accountId }
            .map { it[PayWallets.walletAddress] }
            .firstOrNull()
    }

    /**
     * Idempotent: records the opt-in gate for [accountId] with the address viewrr-pay returned.
     * Safe to call every time `ensureWallet` is called (same shape as the upstream RPC it mirrors).
     */
    suspend fun markOptedIn(accountId: UUID, address: String): Unit = suspendTransaction(db) {
        PayWallets.upsert(
            // No keys → primary key (accountId) is used for conflict detection.
            onUpdate = { it[PayWallets.walletAddress] = address },
        ) {
            it[PayWallets.accountId] = accountId
            it[PayWallets.walletAddress] = address
            it[PayWallets.optedInAt] = Instant.now()
        }
        Unit
    }
}
