package wtf.jobin.plugins

import io.ktor.server.application.*
import org.koin.ktor.ext.inject
import wtf.jobin.config.AppConfig
import wtf.jobin.scanner.LibraryRepository
import wtf.jobin.scanner.LibraryWatcher
import wtf.jobin.scanner.MediaScanner
import wtf.jobin.music.MusicScanner
import wtf.jobin.scanner.ScannerScheduler

fun Application.configureScanner() {
    val cfg by inject<AppConfig>()
    if (cfg.role == wtf.jobin.config.Role.AGENT) return // #97: hub-side scheduler; agent scan is #81
    val libraryRepo by inject<LibraryRepository>()
    val scanner by inject<MediaScanner>()
    val musicScanner by inject<MusicScanner>()
    val watcher by inject<LibraryWatcher>()
    // The Application is a CoroutineScope in Ktor 3.x; its lifetime owns the scan/watch coroutines.
    ScannerScheduler.start(this, libraryRepo, scanner, musicScanner, watcher, cfg.scanner.fallbackIntervalMinutes)
}
