package wtf.jobin.identity

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import java.security.SecureRandom

/**
 * Single-use challenge nonces for the challenge-response auth. [issue] mints + stores a random
 * nonce; [consume] is an atomic GET+DEL so a captured challenge can be spent exactly once
 * (replay guard) — same Lua trick TokenService uses for refresh tokens.
 */
interface ChallengeStore {
    suspend fun issue(): String
    /** True iff [challenge] existed and was unspent; deletes it as a side effect. */
    suspend fun consume(challenge: String): Boolean
}

class RedisChallengeStore(
    private val redis: RedisAsyncCommands<String, String>,
    private val ttlSecs: Long = DEFAULT_TTL_SECS,
) : ChallengeStore {
    private val rng = SecureRandom()

    override suspend fun issue(): String {
        val bytes = ByteArray(32).also(rng::nextBytes)
        val challenge = bytes.joinToString("") { "%02x".format(it) }
        redis.set(key(challenge), "1", SetArgs.Builder.ex(ttlSecs)).await()
        return challenge
    }

    override suspend fun consume(challenge: String): Boolean =
        redis.eval<String?>(CONSUME_LUA, ScriptOutputType.VALUE, key(challenge)).await() != null

    private fun key(challenge: String) = "identity:challenge:$challenge"

    private companion object {
        const val DEFAULT_TTL_SECS = 300L // ponytail: 5 min is plenty for a sign-and-return round trip.
        const val CONSUME_LUA =
            "local v=redis.call('GET',KEYS[1]); if v then redis.call('DEL',KEYS[1]) end; return v"
    }
}
