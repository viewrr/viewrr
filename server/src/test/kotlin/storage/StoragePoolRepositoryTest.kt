package wtf.jobin.storage

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.IdentityAccounts
import wtf.jobin.db.Nodes
import wtf.jobin.db.PoolContentReplicas
import wtf.jobin.db.StoragePoolMembers
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #127 (P2P-ADR 0011): storage-pool repository over H2 (no network). */
class StoragePoolRepositoryTest {

    private fun freshDb(): R2dbcDatabase {
        val name = "pool_" + UUID.randomUUID().toString().replace("-", "")
        return R2dbcDatabase.connect(
            connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1"),
            databaseConfig = R2dbcDatabaseConfig.Builder().also {
                it.explicitDialect = H2Dialect()
            },
        )
    }

    private suspend fun createSchema(db: R2dbcDatabase) = suspendTransaction(db) {
        SchemaUtils.create(IdentityAccounts, Nodes, StoragePoolMembers, PoolContentReplicas)
    }

    private suspend fun newOwner(db: R2dbcDatabase): UUID = suspendTransaction(db) {
        IdentityAccounts.insertAndGetId {
            it[publicKey] = UUID.randomUUID().toString()
            it[createdAt] = Instant.now()
        }.value
    }

    private suspend fun newNode(db: R2dbcDatabase, label: String): UUID = suspendTransaction(db) {
        Nodes.insertAndGetId {
            it[name] = label
            it[createdAt] = Instant.now()
        }.value
    }

    @Test
    fun joinPoolRejectsBelowTwentyPercentFloor() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val owner = newOwner(db)
        val node = newNode(db, "phone")
        val repo = StoragePoolRepository(db)

        assertFailsWith<InsufficientContributionException> {
            repo.joinPool(owner, node, freeSpaceBytes = 1_000, contributedBytes = 150, contributionPct = 20)
        }
        assertEquals(0, repo.poolMemberCount(owner))
    }

    @Test
    fun joinPoolIsIdempotentPerDeviceAndUpdatesPledge() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val owner = newOwner(db)
        val node = newNode(db, "phone")
        val repo = StoragePoolRepository(db)

        repo.joinPool(owner, node, freeSpaceBytes = 1_000, contributedBytes = 200)
        repo.joinPool(owner, node, freeSpaceBytes = 2_000, contributedBytes = 800, contributionPct = 40)

        val members = repo.poolMembers(owner)
        assertEquals(1, members.size)
        assertEquals(800, members.first().contributedBytes)
        assertEquals(40, members.first().contributionPct)
    }

    @Test
    fun singleDeviceUserGetsLoudWarning() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val owner = newOwner(db)
        val node = newNode(db, "only-phone")
        val repo = StoragePoolRepository(db)
        repo.joinPool(owner, node, freeSpaceBytes = 1_000, contributedBytes = 300)
        repo.placeReplica(owner, "vault/passport.jpg", ContentClass.PRIVATE, node)

        assertTrue(repo.singleDeviceWarning(owner))
        val status = repo.durabilityOf(owner, "vault/passport.jpg", ContentClass.PRIVATE)
        assertTrue(status.singleDeviceWarning)
        assertFalse(status.healthy)
        assertEquals(1, status.replicationFactor)
    }

    @Test
    fun privateOriginalReachesRfTwoAcrossTwoDevices() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val owner = newOwner(db)
        val phone = newNode(db, "phone")
        val laptop = newNode(db, "laptop")
        val repo = StoragePoolRepository(db)
        repo.joinPool(owner, phone, 1_000, 300)
        repo.joinPool(owner, laptop, 1_000, 300)

        repo.placeReplica(owner, "vault/passport.jpg", ContentClass.PRIVATE, phone)
        repo.placeReplica(owner, "vault/passport.jpg", ContentClass.PRIVATE, laptop)

        assertEquals(2, repo.replicationFactor(owner, "vault/passport.jpg"))
        assertFalse(repo.singleDeviceWarning(owner))
        val status = repo.durabilityOf(owner, "vault/passport.jpg", ContentClass.PRIVATE)
        assertTrue(status.healthy)
        assertFalse(status.singleDeviceWarning)
        // RF>=2 invariant holds — no throw.
        repo.assertPrivateInvariant(owner, "vault/passport.jpg")
    }

    @Test
    fun privateOriginalAtRfOneInMultiDevicePoolViolatesInvariant() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val owner = newOwner(db)
        val phone = newNode(db, "phone")
        val laptop = newNode(db, "laptop")
        val repo = StoragePoolRepository(db)
        repo.joinPool(owner, phone, 1_000, 300)
        repo.joinPool(owner, laptop, 1_000, 300)

        // Two devices exist but the original only landed on one -> "never RF=1" violation.
        repo.placeReplica(owner, "vault/keys.gpg", ContentClass.PRIVATE, phone)

        assertEquals(2, repo.poolMemberCount(owner))
        assertEquals(1, repo.replicationFactor(owner, "vault/keys.gpg"))
        assertFailsWith<ReplicationInvariantException> {
            repo.assertPrivateInvariant(owner, "vault/keys.gpg")
        }
        Unit
    }

    @Test
    fun placeReplicaIsIdempotentPerDevice() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val owner = newOwner(db)
        val phone = newNode(db, "phone")
        val repo = StoragePoolRepository(db)
        repo.joinPool(owner, phone, 1_000, 300)

        assertTrue(repo.placeReplica(owner, "vault/x", ContentClass.PRIVATE, phone))
        assertFalse(repo.placeReplica(owner, "vault/x", ContentClass.PRIVATE, phone)) // no-op
        assertEquals(1, repo.replicationFactor(owner, "vault/x"))
    }

    @Test
    fun publicCacheAtRfOneIsAllowedNoInvariantOnRemoval() = runBlocking {
        val db = freshDb()
        createSchema(db)
        val owner = newOwner(db)
        val phone = newNode(db, "phone")
        val laptop = newNode(db, "laptop")
        val repo = StoragePoolRepository(db)
        repo.joinPool(owner, phone, 1_000, 300)
        repo.joinPool(owner, laptop, 1_000, 300)

        repo.placeReplica(owner, "public/movie-42", ContentClass.PUBLIC, phone)
        val status = repo.durabilityOf(owner, "public/movie-42", ContentClass.PUBLIC)
        assertTrue(status.healthy) // RF=1 public is fine (LRU-evictable, re-fetchable)

        // Removal of a replica reduces RF (eviction bookkeeping, later slice uses this).
        assertEquals(1, repo.removeReplica(owner, "public/movie-42", phone))
        assertEquals(0, repo.replicationFactor(owner, "public/movie-42"))
    }
}
