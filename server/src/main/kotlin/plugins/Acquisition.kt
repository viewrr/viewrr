package wtf.jobin.plugins

import io.ktor.server.application.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import wtf.jobin.acquisition.AcquisitionService
import wtf.jobin.acquisition.RpcTorrentClient
import wtf.jobin.acquisition.SeedboxHandoff
import wtf.jobin.acquisition.TorrentClient
import wtf.jobin.acquisition.WatchDirTorrentClient
import wtf.jobin.config.AppConfig
import wtf.jobin.config.Role
import wtf.jobin.media.ReacquireService
import java.nio.file.Path

/**
 * Phase 17 ACQUISITION boot wiring (#86..#93). Mirrors [configureScanner]:
 *  - AGENT role does nothing (acquisition is a hub-side feature, like the scanner);
 *  - acquisition.enabled=false returns BEFORE anything is constructed or started, so
 *    a disabled config launches no watcher, builds no torrent client, and leaves the
 *    #86 re-acquire trigger as its pure logging stub (zero regression).
 *
 * Only when enabled do we build the [TorrentClient] (RPC if a url is set, else the
 * watch-dir glue), the [SeedboxHandoff], and the [AcquisitionService], start the
 * blackhole watcher, and install the #86 enqueue hook on [ReacquireService].
 */
fun Application.configureAcquisition() {
    val log = LoggerFactory.getLogger("wtf.jobin.plugins.Acquisition")
    val cfg by inject<AppConfig>()

    // #97-style gate: acquisition is hub-side; an AGENT owns no DB and runs none of it.
    if (cfg.role == Role.AGENT) {
        ReacquireService.enqueueHook = null // clear any hook from a prior boot in a shared JVM
        return
    }

    val acq = cfg.acquisition
    if (!acq.enabled) {
        // The single guard that makes the whole feature inert. No coroutine, no FS,
        // no torrent client, and ReacquireService keeps its stub behavior (#86).
        // Defensively clear the hook so a disabled re-boot in a shared test JVM cannot
        // leak a prior enabled boot's enqueue behavior (Cato cross-vendor audit).
        ReacquireService.enqueueHook = null
        log.debug("acquisition disabled (acquisition.enabled=false); skipping Phase 17 wiring")
        return
    }

    val db by inject<R2dbcDatabase>()

    // Pick the torrent glue: RPC when a url is configured, else drop .torrent into the
    // download dir as a watch dir. Both stub the real network bits (// TODO needs
    // torrent client deployed) — viewrr orchestrates, it is not a BitTorrent engine.
    val torrentClient: TorrentClient = if (acq.torrentRpcUrl != null) {
        RpcTorrentClient(acq.torrentRpcUrl, acq.torrentRpcToken.orEmpty())
    } else {
        WatchDirTorrentClient(Path.of(acq.downloadDir.ifBlank { System.getProperty("java.io.tmpdir") }))
    }

    val handoff = SeedboxHandoff(db, torrentClient)
    val service = AcquisitionService(db, acq, torrentClient, handoff)

    // Application is a CoroutineScope in Ktor 3.x; its lifetime owns the watch loop.
    service.start(this)

    // #86: wire the re-acquire trigger to enqueue acquisition. Set ONLY when enabled,
    // so a disabled build never installs the hook and ReacquireService.trigger stays a
    // pure debounced log (its 3 callers — StremioService, HlsTranscoder, PlaybackRoutes
    // — see no behavior change).
    ReacquireService.enqueueHook = { titleId -> service.enqueueTitle(titleId) }
    log.info("Phase 17 acquisition enabled: blackhole watch + #86 re-acquire hook installed")
}
