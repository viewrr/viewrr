package wtf.jobin

import com.auth0.jwk.JwkProviderBuilder // #113: JWKS-backed RS256 verification for Keycloak OIDC
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
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig

/** Session payload stored in Redis behind the VIEWRR_SESSION cookie. */
@Serializable
data class UserSession(val userId: String, val isAdmin: Boolean)

fun Application.configureSecurity() {
    val cfg by inject<AppConfig>()
    // #97: stateless AGENT has no Redis/JWT — it serves only register + /raw (token-guarded).
    if (cfg.role == wtf.jobin.config.Role.AGENT) return
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

    // Phase 20 (#113): dual-mode JWT verification. When OIDC is configured
    // (oidcIssuer + oidcJwksUrl both set) viewrr acts as an OIDC resource server and validates
    // Keycloak-issued RS256 tokens via the realm JWKS. Otherwise the legacy HS256 path runs
    // BYTE-IDENTICAL to before. The `admin` boolean claim is read by routes' assertAdmin() in
    // both modes (Keycloak maps realm role -> admin claim, docs/runbooks/keycloak.md §5).
    // Legacy /auth/login + argon2 removal is the post-cutover step (#115); not touched here.
    // ponytail: RS256 activates only with OIDC config present; verifiable only vs a live Keycloak.
    val oidcEnabled = !cfg.auth.oidcIssuer.isNullOrBlank() && !cfg.auth.oidcJwksUrl.isNullOrBlank()
    val allowedClients = cfg.auth.allowedClients // #118: approved frontend client_ids (azp allowlist)
    if (oidcEnabled) {
        log.info("viewrr.auth.oidc* set — validating Keycloak RS256 tokens via JWKS (issuer=${cfg.auth.oidcIssuer}).")
        if (allowedClients.isNotEmpty()) log.info("viewrr.auth.allowedClients set — azp allowlist active: $allowedClients")
    }

    authentication {
        jwt("auth-jwt") {
            realm = cfg.auth.jwtRealm
            if (oidcEnabled) {
                // #113: RS256 via Keycloak realm JWKS. JwkProvider is cached + rate-limited so
                // we don't hit the realm cert endpoint on every request.
                val jwkProvider = JwkProviderBuilder(java.net.URI(cfg.auth.oidcJwksUrl).toURL())
                    .cached(10, 24, java.util.concurrent.TimeUnit.HOURS)
                    .rateLimited(10, 1, java.util.concurrent.TimeUnit.MINUTES)
                    .build()
                verifier(jwkProvider, cfg.auth.oidcIssuer!!) {
                    // Keycloak access tokens are RS256; the audience may be the client id or
                    // "account". We accept the configured legacy audience when present to keep
                    // existing clients working, and otherwise rely on issuer + signature.
                    if (cfg.auth.jwtAudience.isNotBlank()) withAudience(cfg.auth.jwtAudience)
                    acceptLeeway(3)
                }
            } else {
                // Legacy HS256 — unchanged from pre-#113.
                verifier(
                    JWT.require(Algorithm.HMAC256(cfg.auth.jwtSecret))
                        .withIssuer(cfg.auth.jwtIssuer)
                        .withAudience(cfg.auth.jwtAudience)
                        .build()
                )
            }
            validate { credential ->
                if (credential.payload.subject == null) return@validate null
                // #118: in OIDC mode, only accept tokens minted for an approved frontend (azp allowlist).
                // Even a valid user token issued to some other client_id is refused. Empty list = allow any
                // (back-compat). Honest ceiling: a determined party can still build a client using our
                // public client_id — this gates registered clients, not binary authenticity (see CONNECT docs).
                if (oidcEnabled && allowedClients.isNotEmpty()) {
                    val azp = credential.payload.getClaim("azp").asString()
                    if (azp == null || azp !in allowedClients) return@validate null
                }
                JWTPrincipal(credential.payload)
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
