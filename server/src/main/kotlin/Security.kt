package wtf.jobin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig

@Serializable
data class UserSession(val userId: String, val isAdmin: Boolean)

fun Application.configureSecurity() {
    val cfg by inject<AppConfig>()
    val redis by inject<RedisAsyncCommands<String, String>>()

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

    install(Sessions) {
        cookie<UserSession>("VIEWRR_SESSION", RedisSessionStorage(redis)) {
            cookie.httpOnly = true
            cookie.extensions["SameSite"] = "lax"
            cookie.maxAgeInSeconds = cfg.auth.refreshTtlDays * 24 * 60 * 60
        }
    }
}

private class RedisSessionStorage(
    private val redis: RedisAsyncCommands<String, String>,
) : SessionStorage {
    override suspend fun write(id: String, value: String) {
        redis.set("sess:$id", value).await()
    }

    override suspend fun read(id: String): String =
        redis.get("sess:$id").await() ?: throw NoSuchElementException(id)

    override suspend fun invalidate(id: String) {
        redis.del("sess:$id").await()
    }
}
