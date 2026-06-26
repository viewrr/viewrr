package wtf.jobin.acquisition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Phase 17 ACQUISITION (#87..#93): the control surface for an external BitTorrent
 * client. viewrr never embeds a BitTorrent engine in-process — it ORCHESTRATES a
 * deployed client either over an RPC channel or by feeding a watch dir. This
 * interface is the only torrent seam the acquisition orchestrator (#88/#89
 * [SeedboxHandoff]) is allowed to touch.
 *
 * ADR-0004 (seed-on-owner): seeding happens on the OWNER, never the transient
 * Downloader, and the swarm must never lose its last seed. [isAnnouncing] is
 * deliberately separate from `pollStatus() == COMPLETE`: "finished on disk" is a
 * weaker fact than "verified and announced to the tracker/DHT". The no-gap delete
 * in the handoff gates on [isAnnouncing], not on COMPLETE.
 *
 * All methods are suspend + main-safe (impls confine blocking work to
 * Dispatchers.IO). ponytail: default OFF — nothing here is constructed or called
 * unless acquisition.enabled=true (see plugins/Acquisition.kt).
 */
interface TorrentClient {

    /**
     * Hand a torrent to the client. [source] is a magnet URI or a path to a
     * .torrent file. [dataDir] is where the client should place / find the
     * payload (the Owner's permanent copy location for a seed).
     *
     * @return an opaque [TorrentHandle] for subsequent polling/removal.
     * @throws TorrentClientException if the client rejects or is unreachable.
     */
    suspend fun addTorrent(source: TorrentSource, dataDir: Path): TorrentHandle

    /**
     * Force the client to re-hash on-disk data against the torrent. Required on
     * the Owner after the file is copied in, so the client recognises the payload
     * as complete instead of re-downloading it (ADR-0004 step c).
     */
    suspend fun forceRecheck(handle: TorrentHandle)

    /** Current lifecycle state. COMPLETE = all pieces verified on disk. */
    suspend fun pollStatus(handle: TorrentHandle): TorrentStatus

    /**
     * True once the client is actively announcing this torrent to the tracker /
     * DHT as a seed — i.e. the swarm can discover us as a source. This is the gate
     * the no-gap invariant depends on (ADR-0004).
     */
    suspend fun isAnnouncing(handle: TorrentHandle): Boolean

    /**
     * Remove the torrent from the client. [deleteData] true also deletes the
     * payload from disk (used to drop the transient Downloader copy AFTER the
     * Owner is announcing). Idempotent: removing an unknown handle is a no-op.
     */
    suspend fun removeTorrent(handle: TorrentHandle, deleteData: Boolean)
}

/** Opaque client-assigned identity for a managed torrent. */
@JvmInline
value class TorrentHandle(val value: String)

/** A magnet URI or a local .torrent file — the two ways to add a torrent. */
sealed interface TorrentSource {
    data class Magnet(val uri: String) : TorrentSource
    data class TorrentFile(val path: Path) : TorrentSource
}

/**
 * Lifecycle states we care about. Closed enum so an impl cannot leak an
 * undeclared state into the orchestrator's `when`.
 */
enum class TorrentStatus {
    /** Added but not yet hashing/transferring. */
    QUEUED,

    /** Verifying on-disk pieces (e.g. after forceRecheck). */
    CHECKING,

    /** Pieces still being fetched from the swarm. */
    DOWNLOADING,

    /** All pieces present and verified on disk. NOT necessarily announcing yet. */
    COMPLETE,

    /** Complete AND actively uploading to peers. */
    SEEDING,

    /** Client reported an error for this torrent. */
    ERRORED,

    /** Handle is unknown to the client (e.g. already removed). */
    UNKNOWN,
}

/** Raised when the external client is unreachable or rejects an operation. */
class TorrentClientException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * #87..#92: RPC-driven client (Transmission rpc / qBittorrent WebUI style). Talks
 * to a configured base URL with a token. Network bits are stubbed until a client
 * is deployed — every stub compiles and returns a defensible default.
 *
 * ponytail: chosen at the boot plugin when acquisition.torrentRpcUrl is set.
 */
class RpcTorrentClient(
    private val baseUrl: String,
    private val token: String,
) : TorrentClient {

    private val log = LoggerFactory.getLogger("wtf.jobin.acquisition.RpcTorrentClient")

    override suspend fun addTorrent(source: TorrentSource, dataDir: Path): TorrentHandle =
        withContext(Dispatchers.IO) {
            val descriptor = when (source) {
                is TorrentSource.Magnet -> source.uri
                is TorrentSource.TorrentFile -> source.path.toString()
            }
            log.info("addTorrent -> rpc {} dataDir={} src={}", baseUrl, dataDir, descriptor)
            // TODO needs torrent client deployed (#87): POST torrent-add to the RPC
            // endpoint (baseUrl + token) with `download-dir=dataDir`, parse the
            // returned hash. Until then, derive a stable deterministic handle so the
            // rest of the pipeline can run end-to-end against a stub.
            TorrentHandle("rpc:" + Integer.toHexString(descriptor.hashCode()))
        }

    override suspend fun forceRecheck(handle: TorrentHandle): Unit =
        withContext(Dispatchers.IO) {
            log.info("forceRecheck -> {}", handle.value)
            // TODO needs torrent client deployed (#90): RPC torrent-verify on the
            // Owner so the copied-in file is recognised as complete.
        }

    override suspend fun pollStatus(handle: TorrentHandle): TorrentStatus =
        withContext(Dispatchers.IO) {
            // TODO needs torrent client deployed (#88): RPC torrent-get status field
            // -> map to TorrentStatus. Stub returns COMPLETE; callers MUST still gate
            // the no-gap delete on isAnnouncing(), not on this value.
            log.debug("pollStatus -> {} (stub COMPLETE)", handle.value)
            TorrentStatus.COMPLETE
        }

    override suspend fun isAnnouncing(handle: TorrentHandle): Boolean =
        withContext(Dispatchers.IO) {
            // TODO needs torrent client deployed (#89): inspect tracker/DHT announce
            // state. Stub returns false so a real deployment is REQUIRED before the
            // handoff will ever delete the Downloader copy — failing closed keeps the
            // no-gap invariant safe by default.
            log.debug("isAnnouncing -> {} (stub false; client not deployed)", handle.value)
            false
        }

    override suspend fun removeTorrent(handle: TorrentHandle, deleteData: Boolean): Unit =
        withContext(Dispatchers.IO) {
            log.info("removeTorrent -> {} deleteData={}", handle.value, deleteData)
            // TODO needs torrent client deployed (#92): RPC torrent-remove with
            // delete-local-data=deleteData. No-op on unknown handle (idempotent).
        }
}

/**
 * #87..#93: watch-dir client — hand off by atomically dropping a .torrent into a
 * directory the deployed client monitors. Status/announce cannot be read back from
 * a watch dir, so those reads stay stubbed and the impl fails closed on
 * isAnnouncing (protects the no-gap invariant).
 *
 * ponytail: chosen at the boot plugin when no RPC url is set but a download dir is.
 */
class WatchDirTorrentClient(
    private val watchDir: Path,
) : TorrentClient {

    private val log = LoggerFactory.getLogger("wtf.jobin.acquisition.WatchDirTorrentClient")

    override suspend fun addTorrent(source: TorrentSource, dataDir: Path): TorrentHandle =
        withContext(Dispatchers.IO) {
            val torrentFile = when (source) {
                is TorrentSource.TorrentFile -> source.path
                is TorrentSource.Magnet ->
                    // A watch dir consumes .torrent files, not magnets.
                    throw TorrentClientException(
                        "WatchDirTorrentClient requires a .torrent file, got a magnet URI",
                    )
            }
            Files.createDirectories(watchDir)
            val dropped = watchDir.resolve(torrentFile.fileName)
            // Atomic move so the client never observes a half-written .torrent.
            Files.move(
                torrentFile,
                dropped,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            log.info("addTorrent -> dropped {} (dataDir={} handled by client config)", dropped, dataDir)
            TorrentHandle("watchdir:" + dropped.fileName.toString())
        }

    override suspend fun forceRecheck(handle: TorrentHandle) {
        log.info("forceRecheck -> {} (watch-dir clients recheck on import)", handle.value)
        // TODO needs torrent client deployed (#90): a pure watch-dir client verifies
        // on import; nothing to trigger here. Deployments exposing a control channel
        // should use RpcTorrentClient instead.
    }

    override suspend fun pollStatus(handle: TorrentHandle): TorrentStatus {
        // TODO needs torrent client deployed (#88): a watch dir gives no status back.
        // Report UNKNOWN rather than lying about completion.
        log.debug("pollStatus -> {} (stub UNKNOWN; watch dir has no read channel)", handle.value)
        return TorrentStatus.UNKNOWN
    }

    override suspend fun isAnnouncing(handle: TorrentHandle): Boolean {
        // TODO needs torrent client deployed (#89): no announce read over a watch dir.
        // Fail closed -> the handoff will not delete the Downloader copy.
        log.debug("isAnnouncing -> {} (stub false; no read channel)", handle.value)
        return false
    }

    override suspend fun removeTorrent(handle: TorrentHandle, deleteData: Boolean) {
        log.info("removeTorrent -> {} deleteData={} (no watch-dir control)", handle.value, deleteData)
        // TODO needs torrent client deployed (#92): a pure watch dir cannot remove a
        // running torrent. Deployments needing removal must use RpcTorrentClient.
    }
}
