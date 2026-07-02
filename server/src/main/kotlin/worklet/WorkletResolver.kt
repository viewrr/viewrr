package wtf.jobin.worklet

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * #121 slice 4 (lookup-only): asks the worklet for candidate peer public keys on a content's swarm.
 * Read-only — nothing is transferred (that's slice 5). This only exposes the capability; no route
 * calls it yet and the HTTP resolveCopy path stays the default.
 *
 * Depends on a plain `callWorklet` lambda (wired to WorkletSupervisor::call) rather than the
 * supervisor type, so it's unit-testable with a capturing lambda — same pattern as WorkletAnnouncer.
 */
class WorkletResolver(
    private val callWorklet: suspend (method: String, params: JsonElement?) -> JsonElement?,
) {
    /** Peer public keys (hex) on hash(contentUuid)'s swarm. Empty when worklet down/disabled or no peers. */
    suspend fun resolvePeers(contentUuidHex: String): List<String> {
        val params = buildJsonObject { put("contentUuid", contentUuidHex) }
        val result = callWorklet("lookup", params) ?: return emptyList()
        val peers = (result as? JsonObject)?.get("peers")?.jsonArray ?: return emptyList()
        return peers.map { it.jsonPrimitive.content }
    }
}
