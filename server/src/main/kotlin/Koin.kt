package wtf.jobin

import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import wtf.jobin.config.AppConfig
import wtf.jobin.config.assertProdSafe
import wtf.jobin.koin.authModule
import wtf.jobin.koin.collectionModule
import wtf.jobin.koin.dbModule
import wtf.jobin.koin.downloadsModule
import wtf.jobin.koin.redisModule
import wtf.jobin.koin.mediaModule
import wtf.jobin.koin.musicModule
import wtf.jobin.koin.recsModule
import wtf.jobin.koin.partyModule
import wtf.jobin.koin.scannerModule
import wtf.jobin.koin.watchModule

fun Application.configureKoin() {
    val appConfig = AppConfig.from(environment)
    assertProdSafe(appConfig)
    install(Koin) {
        slf4jLogger()
        modules(
            module { single { appConfig } },
            dbModule,
            redisModule,
            authModule,
            scannerModule,
            mediaModule,
            recsModule,
            watchModule,
            partyModule,
            downloadsModule,
            collectionModule,
            musicModule,
        )
    }
}
