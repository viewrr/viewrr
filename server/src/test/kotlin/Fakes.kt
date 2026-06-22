package wtf.jobin.auth

import io.lettuce.core.api.async.RedisAsyncCommands
import io.r2dbc.spi.ConnectionFactories
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import wtf.jobin.config.AppConfig
import java.lang.reflect.Proxy
import java.util.UUID

/** HMAC/JWT config for the token + auth tests — pure values, no backend. */
val testAuthConfig = AppConfig.Auth(
    jwtSecret = "test-secret-please-change",
    jwtIssuer = "viewrr",
    jwtAudience = "viewrr-clients",
    jwtRealm = "viewrr",
    accessTtlMinutes = 15L,
    refreshTtlDays = 30L,
)

/**
 * No-op [RedisAsyncCommands]: every method returns null. Safe ONLY where Redis is never
 * actually invoked — the pure JWT `issueAccess` path and the faked refresh path below.
 */
@Suppress("UNCHECKED_CAST")
fun noOpRedis(): RedisAsyncCommands<String, String> =
    Proxy.newProxyInstance(
        RedisAsyncCommands::class.java.classLoader,
        arrayOf(RedisAsyncCommands::class.java),
    ) { _, _, _ -> null } as RedisAsyncCommands<String, String>

// ponytail: never queried — every fake overrides its query methods, so this dormant handle only
// satisfies UserRepository's constructor. explicitDialect skips connect's driver-dialect probe.
val dormantDb: R2dbcDatabase by lazy {
    R2dbcDatabase.connect(
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///viewrr_fake"),
        databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
    )
}

/** In-memory [UserRepository]; counts create() calls and mimics the real case-insensitive lookup. */
class FakeUserRepository : UserRepository(dormantDb) {
    private val byUsername = mutableMapOf<String, UserRow>()
    var createCount = 0
        private set

    fun seed(row: UserRow) { byUsername[row.username.lowercase()] = row }

    override suspend fun findByUsername(username: String): UserRow? = byUsername[username.lowercase()]

    override suspend fun create(
        username: String,
        email: String,
        passwordHash: String,
        displayName: String?,
    ): UserRow {
        createCount++
        val row = UserRow(UUID.randomUUID(), username, email, passwordHash, displayName, isAdmin = false)
        byUsername[username.lowercase()] = row
        return row
    }
}

/** Records verify() invocations (the timing-oracle guard) and lets each test fix the verdict. */
class FakePasswordHasher(private val verifyResult: Boolean = true) : PasswordHasher() {
    var verifyCount = 0
        private set

    override fun hash(plain: CharArray): String = "hash:" + String(plain)

    override fun verify(hash: String, plain: CharArray): Boolean {
        verifyCount++
        return verifyResult
    }
}

/** Issues sentinel tokens; issueRefresh is overridden so Redis is never touched. */
class FakeTokenService : TokenService(testAuthConfig, noOpRedis()) {
    override fun issueAccess(userId: UUID, isAdmin: Boolean): String = "access:$userId:$isAdmin"
    override suspend fun issueRefresh(userId: UUID): String = "refresh:$userId"
}
