package wtf.jobin.koin

import org.koin.dsl.module
import wtf.jobin.config.AppConfig
import wtf.jobin.scanner.Ffprobe
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.scanner.ScannerService

val scannerModule = module {
    single { Ffprobe(get<AppConfig>().media.ffprobePath) }
    single { MediaScanner(get(), get()) }
    single { ScannerService(get()) }
}
