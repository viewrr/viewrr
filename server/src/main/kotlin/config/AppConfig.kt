package wtf.jobin.config

import io.ktor.server.application.*

// Phase 14 (#68): one binary, mode-switched. HUB serves; AGENT is a stateless
// raw-byte store (register + raw-serve only). Route gating lands with those
// endpoints (#69, #75).
enum class Role { HUB, AGENT }

data class AppConfig(
    val role: Role,
    val db: Db,
    val redis: Redis,
    val auth: Auth,
    val media: Media,
    val cors: Cors,
    val recs: Recs,
    val scanner: Scanner,
    val env: String,
    val publicBaseUrl: String,
) {
    data class Db(
        val r2dbcUrl: String,
        val jdbcUrl: String,
        val user: String,
        val password: String,
        val poolMaxSize: Int,
    )

    data class Redis(val uri: String)

    data class Auth(
        val jwtSecret: String,
        val jwtIssuer: String,
        val jwtAudience: String,
        val jwtRealm: String,
        val accessTtlMinutes: Long,
        val refreshTtlDays: Long,
    )

    data class Media(
        val ffprobePath: String,
        val ffmpegPath: String,
        val hlsRoot: String,
        val downloadsRoot: String,
        val tmdbApiKey: String,
    )

    data class Cors(val allowedHosts: List<String>)

    data class Recs(val grpcTarget: String)

    data class Scanner(val fallbackIntervalMinutes: Long)

    companion object {
        fun from(env: ApplicationEnvironment): AppConfig = AppConfig(
            role = env.config.propertyOrNull("viewrr.role")?.getString()
                ?.let { Role.valueOf(it.uppercase()) } ?: Role.HUB,
            db = Db(
                r2dbcUrl = env.config.property("viewrr.db.r2dbcUrl").getString(),
                jdbcUrl = env.config.property("viewrr.db.jdbcUrl").getString(),
                user = env.config.property("viewrr.db.user").getString(),
                password = env.config.property("viewrr.db.password").getString(),
                poolMaxSize = env.config.property("viewrr.db.poolMaxSize").getString().toInt(),
            ),
            redis = Redis(uri = env.config.property("viewrr.redis.uri").getString()),
            auth = Auth(
                jwtSecret = env.config.property("viewrr.auth.jwtSecret").getString(),
                jwtIssuer = env.config.property("viewrr.auth.jwtIssuer").getString(),
                jwtAudience = env.config.property("viewrr.auth.jwtAudience").getString(),
                jwtRealm = env.config.property("viewrr.auth.jwtRealm").getString(),
                accessTtlMinutes = env.config.property("viewrr.auth.accessTtlMinutes").getString().toLong(),
                refreshTtlDays = env.config.property("viewrr.auth.refreshTtlDays").getString().toLong(),
            ),
            media = Media(
                ffprobePath = env.config.property("viewrr.media.ffprobePath").getString(),
                ffmpegPath = env.config.property("viewrr.media.ffmpegPath").getString(),
                hlsRoot = env.config.property("viewrr.media.hlsRoot").getString(),
                downloadsRoot = env.config.property("viewrr.media.downloadsRoot").getString(),
                tmdbApiKey = env.config.propertyOrNull("viewrr.media.tmdbApiKey")?.getString() ?: "",
            ),
            cors = Cors(
                allowedHosts = env.config.propertyOrNull("viewrr.cors.allowedHosts")
                    ?.getList()
                    ?: emptyList(),
            ),
            recs = Recs(
                grpcTarget = env.config.propertyOrNull("viewrr.recs.grpcTarget")?.getString()
                    ?: "localhost:50051",
            ),
            scanner = Scanner(
                fallbackIntervalMinutes = env.config.propertyOrNull("viewrr.scanner.fallbackIntervalMinutes")
                    ?.getString()?.toLong() ?: 15,
            ),
            env = env.config.propertyOrNull("viewrr.env")?.getString() ?: "dev",
            publicBaseUrl = env.config.propertyOrNull("viewrr.publicBaseUrl")?.getString()
                ?: "http://localhost:8080",
        )
    }
}
