package wtf.jobin.pay

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.IdentityAccounts
import wtf.jobin.db.PayWallets
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** mesh-hub: [PayWalletRepository] over H2 (no network) — proves the opt-in gate is DB-backed. */
class PayWalletRepositoryTest {

    private fun freshDb(): R2dbcDatabase {
        val name = "pay_" + UUID.randomUUID().toString().replace("-", "")
        return R2dbcDatabase.connect(
            connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1"),
            databaseConfig = R2dbcDatabaseConfig.Builder().also {
                it.explicitDialect = H2Dialect()
            },
        )
    }

    private suspend fun createSchema(db: R2dbcDatabase) = suspendTransaction(db) {
        SchemaUtils.create(IdentityAccounts, PayWallets)
    }

    private suspend fun newAccount(db: R2dbcDatabase): UUID = suspendTransaction(db) {
        IdentityAccounts.insertAndGetId {
            it[publicKey] = UUID.randomUUID().toString()
            it[createdAt] = Instant.now()
        }.value
    }

    @Test
    fun findAddressIsNullBeforeOptIn() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val account = newAccount(db)
        val repo = PayWalletRepository(db)

        assertNull(repo.findAddress(account))
    }

    @Test
    fun markOptedInPersistsAddressForFindAddress() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val account = newAccount(db)
        val repo = PayWalletRepository(db)

        repo.markOptedIn(account, "0xABC123")

        assertEquals("0xABC123", repo.findAddress(account))
    }

    @Test
    fun markOptedInTwiceIsIdempotentAndDoesNotThrow() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val account = newAccount(db)
        val repo = PayWalletRepository(db)

        repo.markOptedIn(account, "0xABC123")
        repo.markOptedIn(account, "0xABC123") // idempotent re-opt-in, same address every time upstream

        assertEquals("0xABC123", repo.findAddress(account))
    }
}
