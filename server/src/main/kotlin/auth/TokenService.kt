package wtf.jobin.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.lettuce.core.SetArgs
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import wtf.jobin.config.AppConfig
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.UUID

class TokenService(
    private val cfg: AppConfig.Auth,
    private val redis: RedisAsyncCommands<String, String>,
) {
    private val algo = Algorithm.HMAC256(cfg.jwtSecret)
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun issueAccess(userId: UUID, isAdmin: Boolean): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(cfg.jwtIssuer)
            .withAudience(cfg.jwtAudience)
            .withSubject(userId.toString())
            .withClaim("admin", isAdmin)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + cfg.accessTtlMinutes * 60_000))
            .sign(algo)
    }

    suspend fun issueRefresh(userId: UUID): String {
        val bytes = ByteArray(32).also(rng::nextBytes)
        val token = b64.encodeToString(bytes)
        redis.set(
            "refresh:$token",
            userId.toString(),
            SetArgs.Builder.ex(cfg.refreshTtlDays * 24 * 60 * 60),
        ).await()
        return token
    }

    /** Returns userId if the token exists; deletes it (single-use). Caller must mint a fresh pair. */
    suspend fun consumeRefresh(token: String): UUID? {
        val key = "refresh:$token"
        val userId = redis.get(key).await() ?: return null
        redis.del(key).await()
        return UUID.fromString(userId)
    }

    suspend fun revokeRefresh(token: String) {
        redis.del("refresh:$token").await()
    }
}
