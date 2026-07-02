package wtf.jobin.db

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertAndGetId
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * #120 B1 (principal unification): the user-scoped feature tables now FK to identity_accounts
 * (the sole auth principal after #150) instead of the legacy users table. This pins that an
 * identity subject id — the value the write paths pass (JWT sub = identity_accounts.id) — is
 * ACCEPTED by every one of those FKs, and that a principal absent from identity_accounts is
 * REJECTED. Uses the in-memory H2 R2DBC + SchemaUtils.create pattern (H2 enforces FKs), matching
 * MaxRatingResolveTest; the Exposed table definitions are the same ones SchemaUtils builds from,
 * so this exercises exactly the repointed references.
 *
 * Covers all six repointed FKs: watch_events, downloads, party_rooms(owner) + party_members,
 * user_recommendations, collections(owner).
 */
class PrincipalFkTest {

    private fun freshDb(): R2dbcDatabase {
        val name = "fk_" + UUID.randomUUID().toString().replace("-", "")
        return R2dbcDatabase.connect(
            connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///$name;DB_CLOSE_DELAY=-1"),
            databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
        )
    }

    private val allTables = arrayOf(
        IdentityAccounts, Nodes, Libraries, MediaItems,
        WatchEvents, Downloads, PartyRooms, PartyMembers, UserRecommendations, Collections,
    )

    /** identity_accounts row + Node->Library->MediaItem chain the feature rows reference. */
    private data class Fixture(val identityId: UUID, val mediaId: UUID)

    private suspend fun seed(now: Instant): Fixture {
        val identityId = IdentityAccounts.insertAndGetId {
            it[publicKey] = "aa".repeat(32)
            it[createdAt] = now
        }.value
        val nodeId = Nodes.insertAndGetId {
            it[name] = "node"
            it[createdAt] = now
        }.value
        val libraryId = Libraries.insertAndGetId {
            it[Libraries.nodeId] = nodeId
            it[name] = "lib"
            it[kind] = "movies"
            it[rootPath] = "/data"
            it[createdAt] = now
        }.value
        val mediaId = MediaItems.insertAndGetId {
            it[MediaItems.libraryId] = libraryId
            it[MediaItems.nodeId] = nodeId
            it[title] = "Movie"
            it[originalPath] = "/data/movie.mkv"
            it[createdAt] = now
            it[updatedAt] = now
        }.value
        return Fixture(identityId, mediaId)
    }

    @Test
    fun identitySubjectIsAcceptedByEveryUserScopedFk() = runBlocking {
        val db = freshDb()
        suspendTransaction(db) {
            SchemaUtils.create(*allTables)
            val now = Instant.now()
            val (identityId, mediaId) = seed(now)

            // watch_events
            WatchEvents.insert {
                it[userId] = identityId
                it[WatchEvents.mediaId] = mediaId
                it[positionSecs] = 0
                it[eventType] = "play"
                it[sessionId] = UUID.randomUUID()
                it[createdAt] = now
            }
            // downloads
            Downloads.insert {
                it[userId] = identityId
                it[Downloads.mediaId] = mediaId
                it[deviceId] = "device-1"
                it[status] = "ready"
                it[expiresAt] = now
                it[createdAt] = now
            }
            // party_rooms (owner) + party_members
            val roomId = PartyRooms.insertAndGetId {
                it[ownerId] = identityId
                it[PartyRooms.mediaId] = mediaId
                it[joinCode] = "ABC12345"
                it[lastSyncedAt] = now
                it[createdAt] = now
            }.value
            PartyMembers.insert {
                it[PartyMembers.roomId] = roomId
                it[userId] = identityId
                it[joinedAt] = now
            }
            // user_recommendations
            UserRecommendations.insert {
                it[userId] = identityId
                it[UserRecommendations.mediaId] = mediaId
                it[score] = 1.0f
                it[rank] = 1
                it[computedAt] = now
            }
            // collections (owner)
            Collections.insert {
                it[ownerId] = identityId
                it[name] = "My List"
                it[createdAt] = now
            }
            Unit
        }
    }

    @Test
    fun nonExistentPrincipalIsRejected() = runBlocking {
        val db = freshDb()
        val now = Instant.now()
        val mediaId = suspendTransaction(db) {
            SchemaUtils.create(*allTables)
            seed(now).mediaId
        }
        val ghost = UUID.randomUUID() // not present in identity_accounts

        // Each violating insert runs in its own transaction (a failed statement aborts its tx).
        assertFailsWith<Exception> {
            suspendTransaction(db) {
                WatchEvents.insert {
                    it[userId] = ghost
                    it[WatchEvents.mediaId] = mediaId
                    it[positionSecs] = 0
                    it[eventType] = "play"
                    it[sessionId] = UUID.randomUUID()
                    it[createdAt] = now
                }
            }
        }
        assertFailsWith<Exception> {
            suspendTransaction(db) {
                Collections.insert {
                    it[ownerId] = ghost
                    it[name] = "Ghost List"
                    it[createdAt] = now
                }
            }
        }
        assertFailsWith<Exception> {
            suspendTransaction(db) {
                PartyRooms.insertAndGetId {
                    it[ownerId] = ghost
                    it[PartyRooms.mediaId] = mediaId
                    it[joinCode] = "GHOST123"
                    it[lastSyncedAt] = now
                    it[createdAt] = now
                }
            }
        }
        Unit
    }
}
