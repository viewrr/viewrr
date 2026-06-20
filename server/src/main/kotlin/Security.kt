package wtf.jobin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig

// ponytail: Sessions + RedisSessionStorage land with Phase 5 (party-sync WebSockets),
// when a route actually needs cookie auth fallback.

fun Application.configureSecurity() {
    val cfg by inject<AppConfig>()

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
    }
}
