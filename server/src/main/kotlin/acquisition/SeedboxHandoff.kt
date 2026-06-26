package wtf.jobin.acquisition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.slf4j.LoggerFactory
import wtf.jobin.db.CopySpec
import wtf.jobin.db.LOCAL_NODE_ID
import wtf.jobin.db.TitleSpec
import wtf.jobin.db.findOrCreateTitle
import wtf.jobin.db.upsertCopy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * #88/#89 (ADR-0004 seed-on-owner): seedbox handoff orchestrator. Moves a finished
 * download from the transient Downloader to the permanent Owner and makes the OWNER
 * the seed, preserving the NO-GAP invariant: the swarm never loses its last seed.
 *
 * Owner   = the triggering node where the Copy permanently lives (LOCAL_NODE_ID /
 *           the Hub for auto re-acquire — see CONTEXT.md Owner).
 * Downloader = the fastest online box (see [Downloader] / #87), transient.
 *
 * The strict order (a..e) below is the safety property of this file. It is
 * deliberately ONE sequential suspend function: each step's output feeds the next,
 * and the delete (e) reads a `val announcing` that only exists after the announce
 * gate (d) succeeds — so the steps cannot be reordered without DELETING code, which
 * is exactly the protection ADR-0004 asks for ("so nobody optimises it back to
 * seed-on-Downloader without knowing why").
 *
 * #92: the Owner's finished file is registered as a Copy here, only after it is a
 * confirmed seed, so the catalog never points at a copy that isn't actually serving.
 *
 * ponytail: default OFF; only invoked when acquisition.enabled=true. Real seed /
 * recheck / announce control is the deployed torrent client's job — see the
 * `// TODO torrent client` markers and [TorrentClient].
 */
class SeedboxHandoff(
    private val db: R2dbcDatabase,
    private val torrentClient: TorrentClient,
    private val config: HandoffConfig = HandoffConfig(),
) {
    private val log = LoggerFactory.getLogger("wtf.jobin.acquisition.SeedboxHandoff")

    /**
     * Execute the handoff for one finished download.
     *
     * Preconditions: the download has COMPLETED on the Downloader and
     * [HandoffRequest.downloaderFile] exists on the Downloader-visible filesystem.
     *
     * @return the persisted [UUID] of the Owner's Copy's Title.
     * @throws TorrentClientException if the Owner never reaches announcing within
     *         [HandoffConfig.announceTimeoutMs]. On that failure we do NOT delete the
     *         Downloader copy — the swarm keeps its seed (no-gap invariant).
     */
    suspend fun execute(request: HandoffRequest): UUID {
        log.info(
            "seedbox handoff start: title='{}' downloader={} owner={}",
            request.title.title, request.downloaderNodeId, request.ownerNodeId,
        )

        // (a) Download completed on the Downloader. The download pipeline is the
        // caller's job; we assert the artifact is actually here before touching
        // anything else, so we fail before we move data we don't have.
        require(Files.exists(request.downloaderFile)) {
            "downloader file missing, refusing handoff: ${request.downloaderFile}"
        }

        // (b) Copy the finished file Downloader -> Owner. The Owner copy is the one
        // that permanently lives in the catalog and seeds. Copy (not move): the
        // Downloader copy must remain a live seed until (d) confirms.
        val ownerFile = copyToOwner(request.downloaderFile, request.ownerDataDir)

        // (c) Owner adds the torrent over its now-local payload, force-rechecks so
        // the client recognises the file as complete, and begins seeding.
        val ownerHandle = torrentClient.addTorrent(request.torrentSource, request.ownerDataDir)
        torrentClient.forceRecheck(ownerHandle)
        // TODO torrent client: a deployed client may need an explicit start/resume
        // after recheck before it begins announcing.

        // (d) WAIT until the Owner is actually announcing to the swarm. This is the
        // no-gap gate. It throws on timeout; control never reaches (e) on failure.
        val announcing = awaitAnnouncing(ownerHandle)
        check(announcing) { "announce gate returned without confirmation (should be impossible)" }
        log.info("owner is announcing handle={}, safe to drop downloader copy", ownerHandle.value)

        // #92: persist the Owner's permanent Copy only after it is a confirmed seed.
        val titleId = persistOwnerCopy(request, ownerFile)

        // (e) ONLY NOW remove the transient Downloader copy. Guarded twice:
        //   - it is lexically after the throwing awaitAnnouncing() above, and
        //   - this require() re-reads the announce confirmation `val`, so the delete
        //     is impossible to reach without (d) having succeeded.
        require(announcing) {
            "no-gap invariant: refusing to delete downloader copy before owner announces"
        }
        dropDownloaderCopy(request)

        log.info("seedbox handoff complete: titleId={} owner is sole/primary seed", titleId)
        return titleId
    }

    /** (b) Copy the payload into the Owner's data dir. */
    private suspend fun copyToOwner(source: Path, ownerDataDir: Path): Path =
        withContext(Dispatchers.IO) {
            Files.createDirectories(ownerDataDir)
            val dest = ownerDataDir.resolve(source.fileName)
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING)
            log.info("copied payload to owner: {} -> {}", source, dest)
            dest
        }

    /**
     * (d) Poll until the Owner is announcing, or throw. Bounded by
     * announceTimeoutMs / pollIntervalMs. ERRORED / UNKNOWN short-circuit to a loud
     * failure — we never silently spin and we never proceed to delete without a
     * positive announce confirmation.
     */
    private suspend fun awaitAnnouncing(handle: TorrentHandle): Boolean {
        val deadline = System.currentTimeMillis() + config.announceTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val status = torrentClient.pollStatus(handle)) {
                TorrentStatus.ERRORED ->
                    throw TorrentClientException("owner torrent errored before announcing: $handle")
                TorrentStatus.UNKNOWN ->
                    // No read channel (e.g. watch-dir client). We cannot verify the
                    // announce, so we must NOT proceed to delete the Downloader copy.
                    throw TorrentClientException(
                        "owner status UNKNOWN (no read channel) — cannot confirm announce for $handle",
                    )
                else -> {
                    if (torrentClient.isAnnouncing(handle)) return true
                    log.debug("owner not announcing yet (status={}), waiting...", status)
                }
            }
            delay(config.pollIntervalMs)
        }
        throw TorrentClientException(
            "owner did not start announcing within ${config.announceTimeoutMs}ms for $handle; " +
                "downloader copy retained (no-gap invariant)",
        )
    }

    /**
     * #92: persist the Owner's permanent Copy into the catalog (media_items /
     * media_copies) via the shared [findOrCreateTitle] + [upsertCopy] helpers, on
     * the Owner node. The Title is matched/created using the request's [TitleSpec]
     * with node/path rewritten to the Owner's on-disk location.
     */
    private suspend fun persistOwnerCopy(request: HandoffRequest, ownerFile: Path): UUID {
        val titleResult = findOrCreateTitle(
            db,
            request.title.copy(nodeId = request.ownerNodeId, originalPath = ownerFile.toString()),
        )
        upsertCopy(
            db,
            titleResult.id,
            CopySpec(
                nodeId = request.ownerNodeId,
                originalPath = ownerFile.toString(),
                sizeBytes = Files.size(ownerFile),
            ),
        )
        return titleResult.id
    }

    /** (e) Remove the transient Downloader copy: torrent + data, then disk. */
    private suspend fun dropDownloaderCopy(request: HandoffRequest) {
        request.downloaderHandle?.let { handle ->
            torrentClient.removeTorrent(handle, deleteData = true)
        }
        // TODO torrent client: when the Downloader runs a *different* client
        // instance/node than `torrentClient`, removal must be addressed to that
        // node's client — wire the per-node client in once multi-node acquisition
        // deploys (#88).
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(request.downloaderFile)
        }
        log.info(
            "dropped downloader copy on node={} file={}",
            request.downloaderNodeId, request.downloaderFile,
        )
    }

    /** Tunables for the announce gate. Defaults are conservative and bounded. */
    data class HandoffConfig(
        val announceTimeoutMs: Long = 120_000,
        val pollIntervalMs: Long = 2_000,
    )

    /**
     * One handoff unit of work. `ownerNodeId` defaults to LOCAL_NODE_ID (the Hub is
     * the Owner for auto re-acquire, per CONTEXT.md).
     */
    data class HandoffRequest(
        val title: TitleSpec,
        val torrentSource: TorrentSource,
        val downloaderNodeId: UUID,
        val downloaderFile: Path,
        val ownerDataDir: Path,
        val downloaderHandle: TorrentHandle? = null,
        val ownerNodeId: UUID = LOCAL_NODE_ID,
    )
}
