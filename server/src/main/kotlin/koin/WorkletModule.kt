package wtf.jobin.koin

import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.dsl.module
import wtf.jobin.config.AppConfig
import wtf.jobin.worklet.AnnounceRepository
import wtf.jobin.worklet.ClearKeyDecryptor
import wtf.jobin.worklet.ProcessSpawner
import wtf.jobin.worklet.RealProcessSpawner
import wtf.jobin.worklet.WorkletAnnouncer
import wtf.jobin.worklet.WorkletResolver
import wtf.jobin.worklet.WorkletSupervisor

/**
 * #121 slice 1 (P2P-ADR 0003): DI for the worklet subprocess seam.
 *
 * Lazy singletons — merely registering this module spawns nothing. The supervisor only spawns a
 * process when [Koin.kt][wtf.jobin.configureKoin] starts it AND `worklet.enabled` is true; a
 * disabled boot never even constructs it (nothing injects it).
 */
val workletModule = module {
    single<ProcessSpawner> { RealProcessSpawner() }
    single { WorkletSupervisor(get<AppConfig>().worklet, get<ProcessSpawner>()) }
    // #121 slice 3: announce local content to the swarm. Also lazy — only started when enabled.
    single { AnnounceRepository(get<R2dbcDatabase>()) }
    single {
        val supervisor = get<WorkletSupervisor>()
        WorkletAnnouncer(get<AnnounceRepository>(), supervisor::call, get<AppConfig>().worklet.announceIntervalMs)
    }
    // #121 slice 4: lookup-only capability. Lazy single, no loop, wired to no route yet — slice 5
    // is what consumes it. ponytail: exists so #124/#125 have a seam; default resolve path untouched.
    single { WorkletResolver(get<WorkletSupervisor>()::call) }
    // #121 slice 5a: in-worklet clear-key decrypt. Capability only — no route consumes it yet.
    single { ClearKeyDecryptor(get<WorkletSupervisor>()::call) }
}
