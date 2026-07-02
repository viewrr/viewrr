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

// ponytail: never queried — FakeIdentityAccountRepository overrides its query methods, so this
// dormant handle only satisfies the repository constructor. explicitDialect skips connect's
// driver-dialect probe. (#120: the user/password fakes went with the retired argon2 login.)
val dormantDb: R2dbcDatabase by lazy {
    R2dbcDatabase.connect(
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///viewrr_fake"),
        databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
    )
}

/** Issues sentinel tokens; issueRefresh is overridden so Redis is never touched. */
class FakeTokenService : TokenService(testAuthConfig, noOpRedis()) {
    override fun issueAccess(userId: UUID, isAdmin: Boolean): String = "access:$userId:$isAdmin"
    override suspend fun issueRefresh(userId: UUID): String = "refresh:$userId"
}
