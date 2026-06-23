package wtf.jobin.koin

import io.lettuce.core.RedisClient
import io.lettuce.core.api.async.RedisAsyncCommands
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.dsl.module
import wtf.jobin.auth.AuthService
import wtf.jobin.auth.PasswordHasher
import wtf.jobin.auth.TokenService
import wtf.jobin.auth.UserRepository
import wtf.jobin.config.AppConfig
import wtf.jobin.collection.CollectionRepository
import wtf.jobin.db.connectDatabase
import wtf.jobin.media.MediaSearchService
import wtf.jobin.music.MusicProbe
import wtf.jobin.music.MusicRepository
import wtf.jobin.music.MusicScanner
import wtf.jobin.recs.RecEngineClient
import wtf.jobin.recs.RecsRepository
import wtf.jobin.party.PartyHub
import wtf.jobin.party.PartyRoomRepository
import wtf.jobin.scanner.Ffprobe
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

val authModule = module {
    single { PasswordHasher() }
    single { TokenService(get<AppConfig>().auth, get()) }
    single { UserRepository(get()) }
    single { AuthService(get(), get(), get()) }
}

val scannerModule = module {
    single { Ffprobe(get<AppConfig>().media.ffprobePath) }
    single { HlsTranscoder(get(), get<AppConfig>().media.ffmpegPath, get<AppConfig>().media.ffprobePath, get<AppConfig>().media.hlsRoot) }
    single { TmdbClient(get<AppConfig>().media.tmdbApiKey) }
    single { HlsCacheManager(java.nio.file.Path.of(get<AppConfig>().media.hlsRoot), get(), get<AppConfig>().media.hlsCacheMaxBytes) } // #80
    single { TranscodeCoordinator(get(), get()) } // Phase 15 (#75/#80) lazy transcode + cache cap
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
