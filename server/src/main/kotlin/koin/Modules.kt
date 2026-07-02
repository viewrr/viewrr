package wtf.jobin.koin

import io.lettuce.core.RedisClient
import io.lettuce.core.api.async.RedisAsyncCommands
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.dsl.module
import wtf.jobin.auth.TokenService
import wtf.jobin.auth.UserRepository
import wtf.jobin.config.AppConfig
import wtf.jobin.collection.CollectionRepository
import wtf.jobin.db.connectDatabase
import wtf.jobin.identity.ChallengeStore
import wtf.jobin.identity.IdentityAccountRepository
import wtf.jobin.identity.IdentityService
import wtf.jobin.identity.RedisChallengeStore
import wtf.jobin.media.MediaSearchService
import wtf.jobin.music.MusicProbe
import wtf.jobin.music.MusicRepository
import wtf.jobin.music.MusicScanner
import wtf.jobin.recs.RecEngineClient
import wtf.jobin.recs.RecsRepository
import wtf.jobin.party.PartyHub
import wtf.jobin.party.PartyRoomRepository
import wtf.jobin.scanner.Ffprobe
import wtf.jobin.scanner.HlsEdgePusher
import wtf.jobin.scanner.HlsTranscoder
import wtf.jobin.scanner.LibraryRepository
import wtf.jobin.scanner.LibraryWatcher
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.scanner.TranscodeCoordinator
import wtf.jobin.scanner.HlsCacheManager
import wtf.jobin.scanner.TmdbClient
import wtf.jobin.downloads.DownloadService
import wtf.jobin.downloads.Mp4Downloader
import wtf.jobin.watch.ContinueWatchingService
import wtf.jobin.watch.WatchEventRepository

val dbModule = module {
    single<R2dbcDatabase> { connectDatabase(get<AppConfig>().db) }
}

val redisModule = module {
    // PartyHub needs the raw RedisClient to open a pub/sub connection alongside the
    // shared command connection. Both are eager so failures surface at boot.
    single<RedisClient>(createdAtStart = true) { RedisClient.create(get<AppConfig>().redis.uri) }
    single<RedisAsyncCommands<String, String>>(createdAtStart = true) {
        get<RedisClient>().connect().async()
    }
    single { wtf.jobin.stremio.StremioKeys(get<RedisAsyncCommands<String, String>>()) }
}

// #120 (P2P-ADR 0001): the argon2 login (AuthService, PasswordHasher) is retired — identity is the
// sole auth path. KEPT: TokenService mints the HS256 access/refresh tokens IdentityService hands
// out after a verified challenge; UserRepository backs the admin user-management + parental-controls
// routes (#49/#50), which are authorization features, not Keycloak. See needs-human note in the PR:
// parental maxRating does not yet resolve for identity subjects (users-table-keyed).
val authModule = module {
    single { TokenService(get<AppConfig>().auth, get()) }
    single { UserRepository(get()) }
}

// #120 (P2P-ADR 0001): self-custody identity — the sole auth path. Reuses TokenService (session
// tokens) + Redis (challenge nonces); admin is granted via the config pubkey allowlist.
val identityModule = module {
    single { IdentityAccountRepository(get()) }
    single<ChallengeStore> { RedisChallengeStore(get<RedisAsyncCommands<String, String>>()) }
    single { IdentityService(get(), get(), get(), get<AppConfig>().auth.adminPublicKeys) }
}

val scannerModule = module {
    single { Ffprobe(get<AppConfig>().media.ffprobePath) }
    single { HlsTranscoder(get(), get<AppConfig>().media.ffmpegPath, get<AppConfig>().media.ffprobePath, get<AppConfig>().media.hlsRoot, get<AppConfig>().cluster.enrollmentSecret) }
    single { TmdbClient(get<AppConfig>().media.tmdbApiKey) }
    single { HlsCacheManager(java.nio.file.Path.of(get<AppConfig>().media.hlsRoot), get(), get<AppConfig>().media.hlsCacheMaxBytes) } // #80
    single {
        // #95 (Phase 18): build the edge pusher only when edgeCacheEnabled; pass null otherwise so
        // the coordinator's edge path stays inert (DEFAULT-OFF = byte-identical to today).
        val cfg = get<AppConfig>()
        val edgePusher = if (cfg.media.edgeCacheEnabled) {
            HlsEdgePusher(get(), java.nio.file.Path.of(cfg.media.hlsRoot), cfg.cluster.enrollmentSecret)
        } else {
            null
        }
        TranscodeCoordinator(get(), get(), edgePusher)
    } // Phase 15 (#75/#80) lazy transcode + cache cap; #95 edge push
    single { MediaScanner(get(), get(), get()) }
    single { LibraryRepository(get()) }
    // Eager so the watcher is ready before #35 wires start() at boot.
    single(createdAtStart = true) { LibraryWatcher(get()) }
    // Phase 14 (#69/#73): node enrollment + token auth.
    single { wtf.jobin.cluster.NodeRegistry(get(), get<AppConfig>().cluster.enrollmentSecret) }
}

val mediaModule = module {
    single { MediaSearchService(get()) }
}

val recsModule = module {
    single { RecsRepository(get()) }
    // Lazy: connection only opens on first refresh call (issue: createdAtStart=false).
    single { RecEngineClient(get<AppConfig>().recs.grpcTarget) }
}

val watchModule = module {
    single { WatchEventRepository(get()) }
    single { ContinueWatchingService(get()) }
}

val partyModule = module {
    single { PartyRoomRepository(get()) }
    single(createdAtStart = true) {
        PartyHub(get<RedisClient>(), get<RedisAsyncCommands<String, String>>(), get<R2dbcDatabase>())
    }
}

val downloadsModule = module {
    single { Mp4Downloader(get(), get<AppConfig>().media.ffmpegPath, get<AppConfig>().media.downloadsRoot) }
    single { DownloadService(get(), get(), get<AppConfig>().auth) }
}

val collectionModule = module {
    single { CollectionRepository(get()) }
}

val musicModule = module {
    single { MusicProbe(get<AppConfig>().media.ffprobePath) }
    single { MusicScanner(get(), get()) }
    single { MusicRepository(get()) }
}

val editorialModule = module {
    single { wtf.jobin.editorial.EditorialRepository(get()) }
    single { wtf.jobin.editorial.EditorialIngestService(get()) }
}
