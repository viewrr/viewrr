package wtf.jobin.koin

import org.koin.dsl.module
import wtf.jobin.config.AppConfig
import wtf.jobin.worklet.ProcessSpawner
import wtf.jobin.worklet.RealProcessSpawner
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
}
