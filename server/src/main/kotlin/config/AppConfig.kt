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
    val cluster: Cluster,
    val agent: Agent,
    val acquisition: Acquisition, // Phase 17 (#86..#93)
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
        // Phase 20 (#113): when set, viewrr validates Keycloak RS256 tokens via JWKS.
        // When null/blank, the legacy HS256 path stays live. See docs/runbooks/keycloak.md.
        val oidcIssuer: String? = null,
        val oidcJwksUrl: String? = null,
    )

    data class Media(
        val ffprobePath: String,
        val ffmpegPath: String,
        val hlsRoot: String,
        val downloadsRoot: String,
        val tmdbApiKey: String,
        val hlsCacheMaxBytes: Long, // Phase 15 (#80) HLS cache cap
        // #95 (Phase 18): edge-cache Hub-produced HLS to the owning same-LAN Node so later plays
        // come over the LAN, not the Hub. DEFAULT-OFF: false reproduces today's behavior exactly
        // (no push after transcode, localityUrl never prefers a Node HLS URL).
        val edgeCacheEnabled: Boolean = false,
        // #95: where an AGENT stores HLS bundles pushed to it (the receive side). Hub-mode ignores it.
        val hlsCacheRoot: String = "/tmp/viewrr-hls-edge",
    )

    data class Cors(val allowedHosts: List<String>)

    data class Recs(val grpcTarget: String)

    data class Scanner(val fallbackIntervalMinutes: Long)

    // Phase 14 (#73): enrollment secret an Agent presents at register to receive a per-node token.
    data class Cluster(val enrollmentSecret: String)

    // Phase 14 (#68): Agent-mode settings. Used only when role=AGENT.
    // name blank -> hostname. tokenFile persists {nodeId, token} so re-boot skips re-register.
    data class Agent(
        val hubBaseUrl: String,
        val name: String,
        val meshAddress: String?,
        val clientAddress: String?,
        val tokenFile: String,
        val libraryRoots: List<String>, // Phase 15 (#76): dirs the raw endpoint may serve from
    )

    // Phase 17 (#86..#93): ACQUISITION. Default OFF and all-blank so a dev box with
    // no acquisition env launches no watcher, dials no torrent RPC, and touches no
    // dir. enabled=false => the whole feature is inert (zero regression).
    //   blackholeDir  #90: arr apps drop grabbed .torrent/.magnet here; viewrr watches it.
    //   downloadDir        Downloader working dir for in-flight fetches.
    //   booksDir      #93: .epub/.pdf land here (acquire-only; never served, no Copy).
    //   torrentRpcUrl/Token: configured torrent client RPC (null/blank => watch-dir glue).
    // #91 Prowlarr: NOT configured here — arr drives indexers; viewrr receives via blackhole.
    data class Acquisition(
        val enabled: Boolean,
        val blackholeDir: String,
        val downloadDir: String,
        val booksDir: String,
        val torrentRpcUrl: String?,
        val torrentRpcToken: String?,
    )

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
                oidcIssuer = env.config.propertyOrNull("viewrr.auth.oidcIssuer")?.getString()?.takeIf { it.isNotBlank() },
                oidcJwksUrl = env.config.propertyOrNull("viewrr.auth.oidcJwksUrl")?.getString()?.takeIf { it.isNotBlank() },
            ),
            media = Media(
                ffprobePath = env.config.property("viewrr.media.ffprobePath").getString(),
                ffmpegPath = env.config.property("viewrr.media.ffmpegPath").getString(),
                hlsRoot = env.config.property("viewrr.media.hlsRoot").getString(),
                downloadsRoot = env.config.property("viewrr.media.downloadsRoot").getString(),
                tmdbApiKey = env.config.propertyOrNull("viewrr.media.tmdbApiKey")?.getString() ?: "",
                hlsCacheMaxBytes = env.config.propertyOrNull("viewrr.media.hlsCacheMaxBytes")?.getString()?.toLong()
                    ?: 53_687_091_200L, // 50 GiB
                // #95: default false -> zero behavior change vs today.
                edgeCacheEnabled = env.config.propertyOrNull("viewrr.media.edgeCacheEnabled")
                    ?.getString()?.toBooleanStrictOrNull() ?: false,
                hlsCacheRoot = env.config.propertyOrNull("viewrr.media.hlsCacheRoot")?.getString()
                    ?: "/tmp/viewrr-hls-edge",
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
            cluster = Cluster(
                enrollmentSecret = env.config.propertyOrNull("viewrr.cluster.enrollmentSecret")
                    ?.getString() ?: "change-me-dev-only",
            ),
            agent = Agent(
                hubBaseUrl = env.config.propertyOrNull("viewrr.agent.hubBaseUrl")
                    ?.getString() ?: "http://localhost:8080",
                name = env.config.propertyOrNull("viewrr.agent.name")?.getString().orEmpty(),
                meshAddress = env.config.propertyOrNull("viewrr.agent.meshAddress")?.getString()
                    ?.takeIf { it.isNotBlank() },
                clientAddress = env.config.propertyOrNull("viewrr.agent.clientAddress")?.getString()
                    ?.takeIf { it.isNotBlank() },
                tokenFile = env.config.propertyOrNull("viewrr.agent.tokenFile")
                    ?.getString() ?: "/tmp/viewrr-agent.json",
                libraryRoots = env.config.propertyOrNull("viewrr.agent.libraryRoots")?.getString()
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            ),
            acquisition = Acquisition(
                // Phase 17 (#86..#93): all optional; enabled defaults false so the feature
                // is inert unless explicitly switched on. Parses cleanly with zero env set.
                enabled = env.config.propertyOrNull("viewrr.acquisition.enabled")
                    ?.getString()?.toBoolean() ?: false,
                blackholeDir = env.config.propertyOrNull("viewrr.acquisition.blackholeDir")
                    ?.getString().orEmpty(),
                downloadDir = env.config.propertyOrNull("viewrr.acquisition.downloadDir")
                    ?.getString().orEmpty(),
                booksDir = env.config.propertyOrNull("viewrr.acquisition.booksDir")
                    ?.getString().orEmpty(),
                torrentRpcUrl = env.config.propertyOrNull("viewrr.acquisition.torrentRpcUrl")
                    ?.getString()?.takeIf { it.isNotBlank() },
                torrentRpcToken = env.config.propertyOrNull("viewrr.acquisition.torrentRpcToken")
                    ?.getString()?.takeIf { it.isNotBlank() },
            ),
            env = env.config.propertyOrNull("viewrr.env")?.getString() ?: "dev",
            publicBaseUrl = env.config.propertyOrNull("viewrr.publicBaseUrl")?.getString()
                ?: "http://localhost:8080",
        )
    }
}
