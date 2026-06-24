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
        // Phase 15 (#97): AGENT is stateless — only config. It owns no DB/Redis and runs just
        // register + raw-serve, so loading the data modules (which open Postgres/Redis at boot)
        // is both wasteful and a hard dependency a NAS agent shouldn't have.
        if (appConfig.role == wtf.jobin.config.Role.AGENT) {
            modules(module { single { appConfig } })
        } else {
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
}
