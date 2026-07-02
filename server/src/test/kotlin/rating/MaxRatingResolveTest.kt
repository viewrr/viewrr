package wtf.jobin.rating

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import wtf.jobin.db.IdentityAccounts
import wtf.jobin.db.Users
import wtf.jobin.identity.IdentityAccountRepository
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #120 fail-open regression guard. After #150 the JWT subject is an identity_accounts.id; maxRatingFor
 * used to query only `users`, so an identity subject never matched → null → unrestricted, silently
 * ignoring any parental cap. These tests pin the fixed behaviour against in-memory H2 R2DBC (the same
 * single-transaction pattern as EditorialRepositoryTest — nested suspendTransaction joins the outer one).
 */
class MaxRatingResolveTest {

    private fun freshDb(): R2dbcDatabase {
        val name = "rating_" + UUID.randomUUID().toString().replace("-", "")
        return R2dbcDatabase.connect(
            connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1"),
            databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
        )
    }

    private suspend fun newIdentity(publicKey: String): UUID = IdentityAccounts.insertAndGetId {
        it[IdentityAccounts.publicKey] = publicKey
        it[IdentityAccounts.createdAt] = Instant.now()
    }.value

    @Test
    fun identitySubjectWithSetMaxRatingIsEnforced() = runBlocking {
        val db = freshDb()
        suspendTransaction(db) {
            SchemaUtils.create(IdentityAccounts, Users)
            val accId = newIdentity("aa".repeat(32))
            // Cap the account at PG (previously ignored: id never matched users → unrestricted).
            IdentityAccounts.update({ IdentityAccounts.id eq accId }) { it[maxRating] = "PG" }

            val resolved = maxRatingFor(db, accId)
            assertEquals("PG", resolved) // the previously-ignored value now RESOLVES for the identity subject
            assertTrue(isVisible(resolved, "G"))
            assertFalse(isVisible(resolved, "R"), "content above the cap must be hidden — the hole is closed")
            assertFalse(isVisible(resolved, null), "unrated content must be hidden for a capped subject")
        }
    }

    @Test
    fun identitySubjectWithNullMaxRatingIsUnrestricted() = runBlocking {
        val db = freshDb()
        suspendTransaction(db) {
            SchemaUtils.create(IdentityAccounts, Users)
            val accId = newIdentity("bb".repeat(32)) // no cap set → adult by default

            val resolved = maxRatingFor(db, accId)
            assertNull(resolved)
            assertTrue(isVisible(resolved, "NC-17"), "no cap → everything visible")
        }
    }

    @Test
    fun setterMakesThePreviouslyIgnoredCapEnforced() = runBlocking {
        val db = freshDb()
        val repo = IdentityAccountRepository(db)
        suspendTransaction(db) {
            SchemaUtils.create(IdentityAccounts, Users)
            val accId = newIdentity("cc".repeat(32))

            // Before the setter runs: unrestricted (matches the null/adult-by-default case).
            assertNull(maxRatingFor(db, accId))
            assertTrue(isVisible(maxRatingFor(db, accId), "R"))

            // Admin/self sets the cap on the identity principal.
            assertTrue(repo.setMaxRating(accId, "PG"))

            // After: the cap resolves AND is enforced end-to-end.
            assertEquals("PG", maxRatingFor(db, accId))
            assertFalse(isVisible(maxRatingFor(db, accId), "R"))
            assertTrue(isVisible(maxRatingFor(db, accId), "G"))

            // Clearing the cap restores unrestricted.
            assertTrue(repo.setMaxRating(accId, null))
            assertNull(maxRatingFor(db, accId))

            // A genuinely unknown id has no account to cap.
            assertFalse(repo.setMaxRating(UUID.randomUUID(), "G"))
        }
    }

    @Test
    fun legacyUsersSubjectStillResolves() = runBlocking {
        val db = freshDb()
        suspendTransaction(db) {
            SchemaUtils.create(IdentityAccounts, Users)
            val now = Instant.now()
            val uid = Users.insertAndGetId {
                it[username] = "legacy"
                it[email] = "legacy@example.com"
                it[passwordHash] = "x"
                it[maxRating] = "G"
                it[createdAt] = now
                it[updatedAt] = now
            }.value

            // No identity row for this id → fall back to users, which carries the cap.
            val resolved = maxRatingFor(db, uid)
            assertEquals("G", resolved)
            assertFalse(isVisible(resolved, "PG-13"))
        }
    }
}
