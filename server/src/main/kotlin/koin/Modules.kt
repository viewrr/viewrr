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
import wtf.jobin.db.connectDatabase
import wtf.jobin.media.MediaSearchService
import wtf.jobin.recs.RecsRepository
import wtf.jobin.party.PartyRoomRepository
import wtf.jobin.scanner.Ffprobe
import wtf.jobin.scanner.HlsTranscoder
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.watch.WatchEventRepository

val dbModule = module {
    single<R2dbcDatabase> { connectDatabase(get<AppConfig>().db) }
}

val redisModule = module {
    single<RedisAsyncCommands<String, String>>(createdAtStart = true) {
        RedisClient.create(get<AppConfig>().redis.uri).connect().async()
    }
}

val authModule = module {
    single { PasswordHasher() }
    single { TokenService(get<AppConfig>().auth, get()) }
    single { UserRepository(get()) }
    single { AuthService(get(), get(), get()) }
}

val scannerModule = module {
    single { Ffprobe(get<AppConfig>().media.ffprobePath) }
    single { HlsTranscoder(get(), get<AppConfig>().media.ffmpegPath, get<AppConfig>().media.hlsRoot) }
    single { MediaScanner(get(), get(), get()) }
}

val mediaModule = module {
    single { MediaSearchService(get()) }
}

val recsModule = module {
    single { RecsRepository(get()) }
}

val watchModule = module {
    single { WatchEventRepository(get()) }
}

val partyModule = module {
    single { PartyRoomRepository(get()) }
}
