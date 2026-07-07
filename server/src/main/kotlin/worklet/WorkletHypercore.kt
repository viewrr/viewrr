package wtf.jobin.worklet

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * #121 inc-2 (P2P-ADR 0003): typed Kotlin surface for the Hyper* worklet methods (`hyperlet.mjs`),
 * spoken over a live [WorkletRpc]. Like [WorkletRpc] this is codec-only — it owns no process and no
 * swarm; a caller wires it to a spawned `bare hyperlet.mjs`. It proves the increment-2 seam:
 * open a Hypercore, append/get blocks, read `length`, and join a Hyperswarm topic to replicate.
 *
 * Deliberately a NEW, self-contained file: it touches no db/Tables or watch/downloads/party/recs
 * write paths (a parallel agent owns those). Hyperbee/Hyperdrive/Autobase surfaces are later
 * increments — this one is only "a core appends and replicates".
 *
 * ponytail: per-method [timeoutMs] because the two calls are not alike. `get` on a read-only replica
 * blocks until the block has REPLICATED, so its timeout is what bounds "the peer never showed up";
 * the control calls resolve locally and want a short leash.
 */
class WorkletHypercore(private val rpc: WorkletRpc) {

    /** open a Hypercore. [keyHex] null → a fresh writable core; non-null → a read-only replica of it. */
    suspend fun coreOpen(keyHex: String? = null, timeoutMs: Long = 8000): CoreInfo {
        val params = keyHex?.let { buildJsonObject { put("keyHex", it) } }
        val obj = rpc.call("coreOpen", params, timeoutMs).jsonObject
        return CoreInfo(
            handle = obj.getValue("handle").jsonPrimitive.long,
            keyHex = obj.getValue("keyHex").jsonPrimitive.content,
            writable = obj.getValue("writable").jsonPrimitive.boolean,
            length = obj.getValue("length").jsonPrimitive.long,
        )
    }

    /** append one utf-8 block; returns the core's new length. */
    suspend fun append(handle: Long, data: String, timeoutMs: Long = 8000): Long {
        val params = buildJsonObject { put("handle", handle); put("data", data) }
        return rpc.call("append", params, timeoutMs).jsonObject.getValue("length").jsonPrimitive.long
    }

    /**
     * read block [seq] as utf-8 (null if the block is null). On a replica this AWAITS replication of
     * that block, so [timeoutMs] is the "peer never delivered" bound — a [WorkletRpcException.Timeout]
     * here means the block did not replicate in time, not a protocol error.
     */
    suspend fun get(handle: Long, seq: Long, timeoutMs: Long = 30000): String? {
        val params = buildJsonObject { put("handle", handle); put("seq", seq) }
        val data = rpc.call("get", params, timeoutMs).jsonObject.getValue("data")
        return if (data is JsonNull) null else data.jsonPrimitive.content
    }

    /** local block count (a replica's grows as it replicates). */
    suspend fun length(handle: Long, timeoutMs: Long = 8000): Long {
        val params = buildJsonObject { put("handle", handle) }
        return rpc.call("length", params, timeoutMs).jsonObject.getValue("length").jsonPrimitive.long
    }

    /**
     * join the 32-byte [topicHex] on a Hyperswarm and replicate this core with peers on it. A
     * writable core joins as server, a replica as client. Returns once the topic is announced/looked
     * up on the DHT — which is why it, too, can time out when the DHT is unreachable.
     */
    suspend fun swarmJoin(handle: Long, topicHex: String, timeoutMs: Long = 30000) {
        val params = buildJsonObject { put("handle", handle); put("topicHex", topicHex) }
        rpc.call("swarmJoin", params, timeoutMs)
    }

    /**
     * mesh-hub (P2P-ADR 0008/0014): read-only DHT presence check. Joins [topicHex] on a fresh,
     * throwaway Hyperswarm as a lookup-only client (no core, nothing announced), counts distinct
     * peer connections found within [waitMs], then leaves and tears the swarm down. Returns a
     * PRESENCE COUNT, never a peer identity — pseudonymity is structural (see [swarmJoin] and
     * `wtf.jobin.availability.AvailabilityService`). [timeoutMs] bounds the whole RPC, so it must
     * exceed [waitMs] by enough margin for DHT announce/lookup + the wait window itself.
     */
    suspend fun swarmLookup(
        topicHex: String,
        waitMs: Long = 3000,
        timeoutMs: Long = waitMs + 15_000,
    ): LookupResult {
        val params = buildJsonObject { put("topicHex", topicHex); put("waitMs", waitMs) }
        val obj = rpc.call("swarmLookup", params, timeoutMs).jsonObject
        return LookupResult(
            topicHex = obj.getValue("topicHex").jsonPrimitive.content,
            peersFound = obj.getValue("peersFound").jsonPrimitive.int,
        )
    }

    data class CoreInfo(
        val handle: Long,
        val keyHex: String,
        val writable: Boolean,
        val length: Long,
    )

    /** Result of [swarmLookup]: a content topic and how many distinct peers answered — no who. */
    data class LookupResult(
        val topicHex: String,
        val peersFound: Int,
    )
}
