package wtf.jobin

import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import wtf.jobin.config.AppConfig
import wtf.jobin.config.Role
import wtf.jobin.config.assertProdSafe
import wtf.jobin.worklet.WorkletSupervisor
import wtf.jobin.koin.authModule
import wtf.jobin.koin.collectionModule
import wtf.jobin.koin.dbModule
import wtf.jobin.koin.downloadsModule
import wtf.jobin.koin.editorialModule
import wtf.jobin.koin.identityModule
import wtf.jobin.koin.redisModule
import wtf.jobin.koin.mediaModule
import wtf.jobin.koin.musicModule
import wtf.jobin.koin.recsModule
import wtf.jobin.koin.partyModule
import wtf.jobin.koin.scannerModule
import wtf.jobin.koin.watchModule
import wtf.jobin.koin.workletModule

fun Application.configureKoin() {
    val appConfig = AppConfig.from(environment)
    assertProdSafe(appConfig)
    install(Koin) {
        slf4jLogger()
        // Phase 15 (#97): AGENT is stateless — only config. It owns no DB/Redis and runs just
        // register + raw-serve, so loading the data modules (which open Postgres/Redis at boot)
        // is both wasteful and a hard dependency a NAS agent shouldn't have.
        if (appConfig.role == Role.AGENT) {
            modules(module { single { appConfig } })
        } else {
            modules(
                module { single { appConfig } },
                dbModule,
                redisModule,
                authModule,
                identityModule,
                scannerModule,
                mediaModule,
                recsModule,
                watchModule,
                partyModule,
                downloadsModule,
                collectionModule,
                musicModule,
                editorialModule,
                workletModule, // #121 slice 1 — DI only; nothing spawns until started below
            )
        }
    }

    // #121 slice 1 (P2P-ADR 0003): start the worklet supervisor ONLY on a HUB with the flag on.
    // Default OFF => nothing spawns at boot. The supervisor is a lazy singleton, so a disabled boot
    // never even constructs it. Mirrors the role/enabled gating used across the app (see #95, #97).
    if (appConfig.role != Role.AGENT && appConfig.worklet.enabled) {
        // 'this' Application is the CoroutineScope whose lifetime owns the subprocess (like #86).
        getKoin().get<WorkletSupervisor>().start(this)
    }
}
