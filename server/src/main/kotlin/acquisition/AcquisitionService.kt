package wtf.jobin.acquisition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.slf4j.LoggerFactory
import wtf.jobin.config.AppConfig
import wtf.jobin.db.LOCAL_NODE_ID
import wtf.jobin.db.TitleSpec
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Phase 17 ACQUISITION orchestrator (#86..#93). viewrr ORCHESTRATES external tools;
 * it never reimplements indexer/search/quality logic.
 *
 *  - #90 blackhole: watch [AppConfig.Acquisition.blackholeDir] for new `.torrent` /
 *    `.magnet` files (an arr app writes a grabbed release there) and enqueue them.
 *  - #87 fastest-node: the [Downloader] picks the fastest ONLINE node to fetch.
 *  - torrent glue: a [TorrentClient] (RPC or watch-dir) drives the actual fetch.
 *  - #88/#89 seedbox handoff: [SeedboxHandoff] moves the finished file to the Owner
 *    and makes the Owner the seed, with the ADR-0004 no-gap invariant.
 *  - #92 post-grab register: on completion the finished file is registered as a Copy
 *    (handled inside [SeedboxHandoff.persistOwnerCopy]).
 *  - #93 books: `.epub` / `.pdf` are ACQUIRE-ONLY — routed into booksDir, never given
 *    a Copy row and never served.
 *  - #86: [enqueueTitle] is the entry point the re-acquire trigger (ReacquireService)
 *    calls when a Title has no online copy.
 *
 * #91 (Prowlarr): NOT handled here. Prowlarr aggregates indexers FOR the arr apps;
 * the arr apps drive search/monitoring and write the grabbed release into the
 * blackhole dir. viewrr only consumes the blackhole — there is intentionally no
 * Prowlarr/indexer client in this codebase.
 *
 * ponytail: default OFF. [start] is a no-op unless acquisition.enabled=true, so a dev
 * box with blank config launches no watcher, dials no RPC, and touches no dir. The
 * acquisition workload has exactly two ingestion points — the blackhole dir and
 * [enqueueTitle] — both reachable only after [start] has run under an enabled config.
 */
class AcquisitionService(
    private val db: R2dbcDatabase,
    private val cfg: AppConfig.Acquisition,
    private val torrentClient: TorrentClient,
    private val handoff: SeedboxHandoff,
) {
    private val log = LoggerFactory.getLogger("wtf.jobin.acquisition.AcquisitionService")

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var watchJob: Job? = null

    @Volatile
    private var watchService: WatchService? = null

    /**
     * Start the blackhole watcher (#90). Idempotent and guarded: if
     * acquisition.enabled is false this returns immediately, launching no coroutine
     * and opening no [WatchService] — the zero-regression guarantee.
     */
    fun start(scope: CoroutineScope) {
        if (!cfg.enabled) {
            log.debug("acquisition disabled; AcquisitionService.start() is a no-op")
            return
        }
        if (cfg.blackholeDir.isBlank()) {
            log.warn("acquisition enabled but blackholeDir is blank; no blackhole watch started (#90)")
            return
        }
        this.scope = scope
        val dir = Path.of(cfg.blackholeDir)
        if (!Files.isDirectory(dir)) {
            // Create it so an arr app can start dropping releases; if that fails, skip.
            runCatching { Files.createDirectories(dir) }
                .onFailure {
                    log.warn("blackholeDir {} is not a directory and could not be created; watch skipped", dir, it)
                    return
                }
        }
        val ws = FileSystems.getDefault().newWatchService()
        runCatching { dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, OVERFLOW) }
            .onFailure {
                runCatching { ws.close() }
                log.error("failed to register blackhole watch on {}", dir, it)
                return
            }
        watchService = ws
        watchJob = scope.launch(Dispatchers.IO) { runEventLoop(dir, ws) }
        log.info("#90 watching blackhole dir {}", dir)
    }

    /** Stop the watcher and release its [WatchService]. Safe to call when never started. */
    fun stop() {
        watchJob?.cancel()
        runCatching { watchService?.close() }
        watchJob = null
        watchService = null
        scope = null
    }

    private suspend fun runEventLoop(dir: Path, ws: WatchService) {
        try {
            while (currentCoroutineContext().isActive) {
                val key: WatchKey = try {
                    ws.take()
                } catch (_: ClosedWatchServiceException) {
                    break
                } catch (_: InterruptedException) {
                    break
                }
                for (raw in key.pollEvents()) {
                    if (raw.kind() == OVERFLOW) continue
                    @Suppress("UNCHECKED_CAST")
                    val event = raw as WatchEvent<Path>
                    val child = dir.resolve(event.context())
                    if (Files.isDirectory(child)) continue
                    when (child.extension.lowercase()) {
                        "torrent" -> enqueueSource(TorrentSource.TorrentFile(child), child.nameWithoutExtension)
                        "magnet" -> enqueueMagnetFile(child)
                        // #93: books are acquire-only — route, never enqueue as a torrent.
                        "epub", "pdf" -> routeBook(child)
                        else -> log.debug("blackhole ignoring non-acquisition file {}", child)
                    }
                }
                if (!key.reset()) break
            }
        } catch (t: Throwable) {
            log.error("blackhole event loop crashed for {}", dir, t)
        } finally {
            runCatching { ws.close() }
        }
    }

    /** A `.magnet` file holds a magnet URI as text; read it and enqueue. */
    private suspend fun enqueueMagnetFile(file: Path) {
        val uri = runCatching { Files.readString(file).trim() }.getOrNull()
        if (uri.isNullOrBlank()) {
            log.warn("blackhole .magnet file {} was empty/unreadable; skipping", file)
            return
        }
        enqueueSource(TorrentSource.Magnet(uri), file.nameWithoutExtension)
    }

    /**
     * Enqueue a grabbed release for acquisition (#90 path). Builds a [TitleSpec] from
     * the release name and runs the orchestration. For the blackhole path the Owner
     * is the Hub (LOCAL_NODE_ID) — auto re-acquisition is Hub-driven (CONTEXT.md).
     *
     * ponytail: title/library resolution from a release name is intentionally thin
     * here — a real impl resolves the matching Title/library from the arr metadata or
     * the existing catalog. // TODO #92: resolve real libraryId + Title metadata.
     */
    private suspend fun enqueueSource(source: TorrentSource, releaseName: String) {
        log.info("#90 enqueue acquisition from blackhole: {} ({})", releaseName, source::class.simpleName)
        runOrchestration(
            source = source,
            title = TitleSpec(
                // TODO #92: resolve the real libraryId for the acquired title.
                libraryId = UUID(0L, 0L),
                title = releaseName,
                cleanTitle = releaseName,
                nodeId = LOCAL_NODE_ID,
                originalPath = "", // filled with the Owner's on-disk path during handoff
            ),
        )
    }

    /**
     * #86 entry point: the re-acquire trigger enqueues a Title that currently has no
     * online copy. Only reachable when acquisition is enabled (the trigger's enqueue
     * hook is set by the boot plugin only then). Here we have a titleId, not a torrent
     * source yet — search is the arr apps' job, so this records the intent and (once
     * the arr roundtrip exists) the grabbed release returns via the blackhole.
     *
     * ponytail: with no in-process indexer, the honest skeleton logs the intent and
     * relies on the blackhole for the actual release. // TODO #87..#91: hand titleId to
     * the arr apps (they search via Prowlarr) and let the grab return via blackhole.
     */
    fun enqueueTitle(titleId: UUID) {
        if (!cfg.enabled) return // belt-and-suspenders; hook is only set when enabled.
        log.info("#86 acquisition requested for titleId={} (await arr grab via blackhole)", titleId)
        // TODO #87..#91: trigger arr search for titleId; grab returns via blackhole dir.
    }

    /**
     * Orchestration glue: pick the Downloader (#87), fetch via the [TorrentClient],
     * then hand off to the Owner (#88/#89) which registers the Copy (#92). The actual
     * fetch/seed/recheck are the deployed torrent client's job.
     */
    private suspend fun runOrchestration(source: TorrentSource, title: TitleSpec) {
        val scope = this.scope ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                // #87: fastest online node fetches. Bandwidth source is empty for now ->
                // defaults to the Hub. // TODO #87: feed measured node bandwidth.
                val downloaderNodeId = Downloader.select(db)
                val downloadDir = Path.of(cfg.downloadDir.ifBlank { System.getProperty("java.io.tmpdir") })
                Files.createDirectories(downloadDir)

                // Tell the client to fetch into the Downloader's working dir.
                val handle = torrentClient.addTorrent(source, downloadDir)
                // TODO needs torrent client deployed (#88): poll the Downloader handle to
                // COMPLETE before handoff. The stub clients don't actually transfer, so we
                // do not fabricate a finished file here — a real deployment provides it.
                log.info(
                    "#88 acquisition fetch started on downloader={} handle={} (handoff to Owner pending real client)",
                    downloaderNodeId, handle.value,
                )

                // #88/#89 + #92: once the Downloader copy is COMPLETE, hand off to the
                // Owner and register the Copy. Guarded behind a real, existing file so the
                // skeleton never invents data; wired for the deployed client to drive.
                // TODO needs torrent client deployed: invoke handoff.execute(...) with the
                // real finished downloaderFile once the client reports COMPLETE, e.g.:
                //   handoff.execute(SeedboxHandoff.HandoffRequest(
                //       title = title, torrentSource = source,
                //       downloaderNodeId = downloaderNodeId, downloaderFile = <finished>,
                //       ownerDataDir = Path.of(cfg.downloadDir), downloaderHandle = handle))
            }.onFailure { log.warn("acquisition orchestration failed for '{}'", title.title, it) }
        }
    }

    /**
     * #93: books are acquire-only. Move the `.epub` / `.pdf` into booksDir and stop —
     * NO Copy row, NO serving. If booksDir is blank, leave the file in place and warn.
     */
    private fun routeBook(file: Path) {
        if (cfg.booksDir.isBlank()) {
            log.warn("#93 book {} grabbed but booksDir is blank; leaving in place (acquire-only, not served)", file)
            return
        }
        runCatching {
            val dest = Path.of(cfg.booksDir)
            Files.createDirectories(dest)
            Files.move(file, dest.resolve(file.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            log.info("#93 routed book {} -> {} (acquire-only; no Copy, not served)", file.fileName, dest)
        }.onFailure { log.warn("#93 failed to route book {}", file, it) }
    }
}
