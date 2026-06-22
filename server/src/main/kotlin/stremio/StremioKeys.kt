package wtf.jobin.stremio

import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Long-lived, revocable per-user key embedded in the Stremio addon install URL.
 * The install URL is a bearer credential, so we use a dedicated random key (not the
 * 15-minute JWT). One key per user, idempotent; resolving it yields the user for
 * parental scoping. Revoke by DEL stremio:key:<key> + stremio:user:<userId>.
 */
class StremioKeys(private val redis: RedisAsyncCommands<String, String>) {
    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    suspend fun keyFor(userId: UUID): String {
        redis.get("stremio:user:$userId").await()?.let { return it }
        val key = b64.encodeToString(ByteArray(24).also(rng::nextBytes))
        redis.set("stremio:key:$key", userId.toString()).await()
        redis.set("stremio:user:$userId", key).await()
        return key
    }

    suspend fun resolve(key: String): UUID? =
        redis.get("stremio:key:$key").await()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
