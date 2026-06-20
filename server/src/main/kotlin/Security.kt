package wtf.jobin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.lettuce.core.SetArgs
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig

/** Session payload stored in Redis behind the VIEWRR_SESSION cookie. */
data class UserSession(val userId: String, val isAdmin: Boolean)

fun Application.configureSecurity() {
    val cfg by inject<AppConfig>()
    val redis by inject<RedisAsyncCommands<String, String>>()
    val sessionTtlSecs = cfg.auth.refreshTtlDays * 86_400L

    install(Sessions) {
        cookie<UserSession>("VIEWRR_SESSION", RedisSessionStorage(redis, sessionTtlSecs)) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = sessionTtlSecs
            cookie.extensions["SameSite"] = "Lax"
        }
    }

    authentication {
        jwt("auth-jwt") {
            realm = cfg.auth.jwtRealm
            verifier(
                JWT.require(Algorithm.HMAC256(cfg.auth.jwtSecret))
                    .withIssuer(cfg.auth.jwtIssuer)
                    .withAudience(cfg.auth.jwtAudience)
                    .build()
            )
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
        }
        session<UserSession>("auth-session") {
            validate { it }
            challenge { call.respond(HttpStatusCode.Unauthorized) }
        }
    }
}

/**
 * Lettuce-backed SessionStorage. Writes use SETEX so abandoned sessions self-expire
 * after [ttlSecs] (= refreshTtlDays * 86400) instead of leaking forever in Redis.
 */
private class RedisSessionStorage(
    private val redis: RedisAsyncCommands<String, String>,
    private val ttlSecs: Long,
) : SessionStorage {
    override suspend fun write(id: String, value: String) {
        redis.set("sess:$id", value, SetArgs.Builder.ex(ttlSecs)).await()
    }

    override suspend fun read(id: String): String =
        redis.get("sess:$id").await() ?: throw NoSuchElementException("session $id not found")

    override suspend fun invalidate(id: String) {
        redis.del("sess:$id").await()
    }
}
