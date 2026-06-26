package wtf.jobin.acquisition

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import wtf.jobin.cluster.nodeOnline
import wtf.jobin.db.LOCAL_NODE_ID
import wtf.jobin.db.Nodes
import java.time.Instant
import java.util.UUID

/**
 * #87: Downloader selection. The Downloader is the fastest ONLINE node (Hub or any
 * Node) — it fetches the torrent quickly, holds the bytes transiently, then hands
 * them to the Owner via [SeedboxHandoff] and deletes its copy (CONTEXT.md Downloader).
 *
 * "Fastest" = highest known download bandwidth among nodes that heartbeated within
 * the online window (reuse [nodeOnline] / #83). Bandwidth is not yet a tracked node
 * field; until it is, every candidate's bandwidth is unknown and we deterministically
 * default to the Hub (LOCAL_NODE_ID), which is always online and is also the Owner for
 * auto re-acquire — so the conservative default never picks an offline or surprising box.
 *
 * ponytail: bandwidth lives in config/cache for now (see [NodeBandwidth]); when a
 * real per-node bandwidth probe/heartbeat field lands it replaces the cache lookup
 * with zero change to callers. // TODO #87: persist measured node bandwidth.
 */
object Downloader {
    private val log = LoggerFactory.getLogger("wtf.jobin.acquisition.Downloader")

    /**
     * A node that is a candidate to be the Downloader, with its last-seen heartbeat
     * (for online filtering) and best-known download bandwidth in bytes/sec (null =
     * unknown).
     */
    data class Candidate(
        val nodeId: UUID,
        val lastSeenAt: Instant?,
        val downloadBytesPerSec: Long?,
    )

    /**
     * Pick the fastest online node id to act as Downloader.
     *
     * Selection rule:
     *   1. keep only ONLINE candidates (LOCAL_NODE_ID is always online; others must
     *      have heartbeated within the [nodeOnline] window);
     *   2. among those, pick the highest known bandwidth;
     *   3. if no online candidate has a known bandwidth (the common case today),
     *      default to the Hub (LOCAL_NODE_ID).
     *
     * @return the chosen Downloader node id; never null (LOCAL is the floor).
     */
    fun selectFastest(candidates: List<Candidate>, now: Instant = Instant.now()): UUID {
        val online = candidates.filter { it.nodeId == LOCAL_NODE_ID || nodeOnline(it.lastSeenAt, now) }
        val fastestKnown = online
            .filter { it.downloadBytesPerSec != null }
            .maxByOrNull { it.downloadBytesPerSec!! }
        val chosen = fastestKnown?.nodeId ?: LOCAL_NODE_ID
        log.info(
            "#87 selected Downloader={} (online={}, hadKnownBandwidth={})",
            chosen, online.size, fastestKnown != null,
        )
        return chosen
    }

    /**
     * Load Downloader candidates from the cluster: every registered node, with its
     * heartbeat. Bandwidth is resolved from [bandwidth] (config/cache) since there is
     * no persisted node-bandwidth column yet.
     *
     * The Hub (LOCAL_NODE_ID) is always included even if it has no `nodes` row, so a
     * single-box install still has a valid (always-online) Downloader = the Hub.
     */
    suspend fun loadCandidates(
        db: R2dbcDatabase,
        bandwidth: NodeBandwidth = NodeBandwidth.Empty,
    ): List<Candidate> {
        val rows: List<Candidate> = suspendTransaction(db) {
            Nodes.select(Nodes.id, Nodes.lastSeenAt)
                .map {
                    val id = it[Nodes.id].value
                    Candidate(id, it[Nodes.lastSeenAt], bandwidth.bytesPerSec(id))
                }
                .toList()
        }
        // Ensure the Hub is always a candidate (single-box installs have no nodes row).
        return if (rows.any { it.nodeId == LOCAL_NODE_ID }) {
            rows
        } else {
            rows + Candidate(LOCAL_NODE_ID, lastSeenAt = null, downloadBytesPerSec = bandwidth.bytesPerSec(LOCAL_NODE_ID))
        }
    }

    /** Convenience: load candidates and pick the fastest in one call. */
    suspend fun select(db: R2dbcDatabase, bandwidth: NodeBandwidth = NodeBandwidth.Empty): UUID =
        selectFastest(loadCandidates(db, bandwidth))
}

/**
 * #87: per-node download bandwidth source. ponytail: a thin indirection over a
 * config/cache map until a measured per-node bandwidth field exists. [Empty] knows
 * nothing -> selection defaults to the Hub.
 */
fun interface NodeBandwidth {
    /** Best-known download bandwidth for [nodeId] in bytes/sec, or null if unknown. */
    fun bytesPerSec(nodeId: UUID): Long?

    companion object {
        /** No bandwidth known for any node -> Downloader defaults to LOCAL/Hub. */
        val Empty: NodeBandwidth = NodeBandwidth { null }

        /** Build from a static map (e.g. config-provided estimates). */
        fun of(map: Map<UUID, Long>): NodeBandwidth = NodeBandwidth { map[it] }
    }
}
