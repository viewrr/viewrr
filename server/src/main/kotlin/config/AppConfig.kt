package wtf.jobin.config

import io.ktor.server.application.*

data class AppConfig(
    val db: Db,
    val redis: Redis,
    val auth: Auth,
    val media: Media,
    val cors: Cors,
    val recs: Recs,
    val env: String,
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
    )

    data class Cors(val allowedHosts: List<String>)

    data class Recs(val grpcTarget: String)

    companion object {
        fun from(env: ApplicationEnvironment): AppConfig = AppConfig(
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
            env = env.config.propertyOrNull("viewrr.env")?.getString() ?: "dev",
        )
    }
}
