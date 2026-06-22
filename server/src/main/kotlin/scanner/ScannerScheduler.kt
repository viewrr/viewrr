package wtf.jobin.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import wtf.jobin.music.MusicScanner
import java.nio.file.Path

/**
 * Boot-time orchestration for library scanning (issue #35):
 *  - one-shot full scan of every watch-enabled library, fire-and-forget so startup never blocks,
 *  - hands each watch-enabled library to [LibraryWatcher] for live filesystem events,
 *  - a single fallback loop that re-scans every [intervalMinutes] to catch missed/overflow events.
 */
object ScannerScheduler {
    private val log = LoggerFactory.getLogger(ScannerScheduler::class.java)

    fun start(
        scope: CoroutineScope,
        libraryRepo: LibraryRepository,
        scanner: MediaScanner,
        musicScanner: MusicScanner,
        watcher: LibraryWatcher,
        intervalMinutes: Long,
    ) {
        // Watcher needs the long-lived scope before watch()/unwatch() (boot + CRUD) do anything.
        watcher.start(scope)

        scope.launch(Dispatchers.IO) {
            val libraries = libraryRepo.list().filter { it.watchEnabled }
            log.info("boot scan: {} watch-enabled libraries", libraries.size)
            for (lib in libraries) {
                if (lib.kind == "music") {
                    // ponytail: music has no live watcher in v1; scan only.
                    scope.launch(Dispatchers.IO) {
                        runCatching { musicScanner.scan(lib.id) }
                            .onFailure { log.warn("boot scan failed for library {}", lib.id, it) }
                    }
                } else {
                    scope.launch(Dispatchers.IO) {
                        runCatching { scanner.scan(lib.id) }
                            .onFailure { log.warn("boot scan failed for library {}", lib.id, it) }
                    }
                    watcher.watch(lib.id, Path.of(lib.rootPath))
                }
            }
        }

        if (intervalMinutes <= 0) {
            log.info("fallback scan disabled (intervalMinutes={})", intervalMinutes)
            return
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(intervalMinutes * 60_000)
                val libraries = libraryRepo.list().filter { it.watchEnabled }
                log.info("fallback cycle: scanning {} libraries", libraries.size)
                for (lib in libraries) {
                    val result = if (lib.kind == "music") {
                        runCatching { musicScanner.scan(lib.id) }
                    } else {
                        runCatching { scanner.scan(lib.id) }
                    }
                    result.onFailure { log.warn("fallback scan failed for library {}", lib.id, it) }
                }
            }
        }
    }
}
