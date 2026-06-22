package wtf.jobin.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension

/**
 * Per-library filesystem watcher backed by [java.nio.file.WatchService] (inotify on Linux,
 * kqueue on the BSDs, polling on macOS). One [WatchService] per library, recursively registering
 * every directory under the library root. Media-relevant events trigger a debounced
 * [MediaScanner.scan] so a burst of file copies coalesces into a single scan pass.
 *
 * Wave 7 lands the watcher behind Koin; wave 8 (#35) wires `start()`/`watch()`/`unwatch()` to the
 * boot sequence and the library CRUD endpoints.
 */
class LibraryWatcher(
    private val mediaScanner: MediaScanner,
) {
    private val log = LoggerFactory.getLogger(LibraryWatcher::class.java)

    // ponytail: macOS JDK WatchService is polling-based (~10s latency). Linux/Windows are
    // event-driven via inotify/kqueue. Accept the latency for now; if 10s feels bad on macOS,
    // swap in io.methvin:directory-watcher (single dep) which uses native FSEvents.
    private val entries = ConcurrentHashMap<UUID, WatchEntry>()

    @Volatile
    private var scope: CoroutineScope? = null

    private class WatchEntry(
        val rootPath: Path,
        val watchService: WatchService,
        val eventJob: Job,
        @Volatile var debounceJob: Job? = null,
    )

    /**
     * Called once at boot by issue #35. Captures the long-lived scope that owns all
     * per-library event coroutines. Subsequent [watch] calls launch under this scope.
     */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        log.info("LibraryWatcher started")
    }

    /**
     * Begin watching [rootPath] for [libraryId]. Idempotent: a second call with the same id
     * unwatches the previous registration first. Safe to call before [start]; the request is
     * dropped with a warning instead of leaking a [WatchService].
     */
    fun watch(libraryId: UUID, rootPath: Path) {
        val activeScope = scope
        if (activeScope == null) {
            log.warn("watch($libraryId, $rootPath) called before start(); ignoring")
            return
        }
        if (!Files.isDirectory(rootPath)) {
            log.warn("watch($libraryId, $rootPath) skipped: not a directory")
            return
        }
        unwatch(libraryId)

        val watchService = FileSystems.getDefault().newWatchService()
        val keyDirs = ConcurrentHashMap<WatchKey, Path>()
        try {
            registerRecursive(watchService, rootPath, keyDirs)
        } catch (t: Throwable) {
            runCatching { watchService.close() }
            log.error("watch($libraryId): registration failed for $rootPath", t)
            return
        }

        val job = activeScope.launch(Dispatchers.IO) {
            runEventLoop(libraryId, rootPath, watchService, keyDirs)
        }
        entries[libraryId] = WatchEntry(rootPath, watchService, job)
        log.info("watching library $libraryId at $rootPath")
    }

    /** Cancel the watcher for [libraryId] and close its [WatchService]. No-op if absent. */
    fun unwatch(libraryId: UUID) {
        val entry = entries.remove(libraryId) ?: return
        entry.debounceJob?.cancel()
        entry.eventJob.cancel()
        runCatching { entry.watchService.close() }
            .onFailure { log.debug("close WatchService for {}", libraryId, it) }
        log.info("unwatched library $libraryId")
    }

    private suspend fun runEventLoop(
        libraryId: UUID,
        rootPath: Path,
        watchService: WatchService,
        keyDirs: ConcurrentHashMap<WatchKey, Path>,
    ) {
        try {
            while (currentCoroutineContext().isActive) {
                val key: WatchKey = try {
                    watchService.take()
                } catch (_: ClosedWatchServiceException) {
                    break
                } catch (_: InterruptedException) {
                    break
                }
                val dir = keyDirs[key] ?: rootPath
                for (raw in key.pollEvents()) {
                    val kind = raw.kind()
                    if (kind == OVERFLOW) {
                        log.warn("WatchService OVERFLOW for library $libraryId; triggering full rescan")
                        runScanNow(libraryId)
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val event = raw as WatchEvent<Path>
                    val child = dir.resolve(event.context())
                    log.debug("fs event {} {} on {}", libraryId, kind.name(), child)
                    when (kind) {
                        ENTRY_CREATE -> {
                            if (Files.isDirectory(child)) {
                                runCatching { registerRecursive(watchService, child, keyDirs) }
                                    .onFailure { log.warn("re-register failed for {}", child, it) }
                                // A directory landing usually means files arrived inside it.
                                scheduleDebouncedScan(libraryId)
                            } else if (isMediaFile(child)) {
                                scheduleDebouncedScan(libraryId)
                            }
                        }
                        ENTRY_MODIFY -> if (isMediaFile(child)) scheduleDebouncedScan(libraryId)
                        ENTRY_DELETE -> if (looksLikeMediaPath(child)) scheduleDebouncedScan(libraryId)
                    }
                }
                val valid = key.reset()
                if (!valid) {
                    keyDirs.remove(key)
                    if (keyDirs.isEmpty()) break
                }
            }
        } catch (t: Throwable) {
            log.error("event loop for library $libraryId crashed", t)
        } finally {
            runCatching { watchService.close() }
            log.debug("event loop for library {} exited (root {})", libraryId, rootPath)
        }
    }

    /** Walk [start] depth-first and register every directory (including [start]) with the watcher. */
    private fun registerRecursive(
        watchService: WatchService,
        start: Path,
        keyDirs: ConcurrentHashMap<WatchKey, Path>,
    ) {
        Files.walk(start).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                try {
                    val key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE, OVERFLOW)
                    keyDirs[key] = dir
                } catch (t: Throwable) {
                    // Subdirectory vanished between walk and register, or perms denied — skip it.
                    log.debug("register failed for {}", dir, t)
                }
            }
        }
    }

    private fun scheduleDebouncedScan(libraryId: UUID) {
        val activeScope = scope ?: return
        val entry = entries[libraryId] ?: return
        entry.debounceJob?.cancel()
        entry.debounceJob = activeScope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MS)
            runScanNow(libraryId)
        }
    }

    private suspend fun runScanNow(libraryId: UUID) {
        runCatching { mediaScanner.scan(libraryId) }
            .onFailure { log.warn("scan after fs event failed for library $libraryId", it) }
    }

    private fun isMediaFile(p: Path): Boolean =
        !Files.isDirectory(p) && p.extension.lowercase() in MEDIA_EXTS

    /** DELETE events fire after the file is gone, so we can't stat — match by extension only. */
    private fun looksLikeMediaPath(p: Path): Boolean =
        p.extension.lowercase() in MEDIA_EXTS

    companion object {
        private const val DEBOUNCE_MS: Long = 2_000
    }
}
