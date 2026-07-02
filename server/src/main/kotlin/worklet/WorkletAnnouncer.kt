package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * #121 slice 3 (announce-only): periodically tells the worklet which content_uuids this deployment
 * holds, so it joins each hash(content_uuid) swarm as a provider. Announce only — no serving.
 *
 * Depends on a plain `callWorklet` lambda (wired to WorkletSupervisor::call) rather than the
 * supervisor type, so the loop is unit-testable with a capturing lambda and a fake repo — no
 * process, no swarm. Only started when `worklet.enabled` (see Koin.kt), so being alive already
 * means the feature is on.
 */
class WorkletAnnouncer(
    private val repo: AnnounceRepository,
    private val callWorklet: suspend (method: String, params: JsonElement?) -> JsonElement?,
    private val intervalMs: Long,
) {
    private val log = LoggerFactory.getLogger(WorkletAnnouncer::class.java)

    /** One announce pass. No-ops (no RPC) when there's nothing to advertise. */
    suspend fun announceOnce() {
        val uuids = repo.localContentUuids()
        if (uuids.isEmpty()) return
        val params = buildJsonObject {
            put("contentUuids", JsonArray(uuids.map { JsonPrimitive(it) }))
        }
        callWorklet("announce", params)
    }

    /** Announce immediately, then every [intervalMs]. One failed pass never kills the loop. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { announceOnce() }.onFailure { log.warn("worklet announce pass failed", it) }
            delay(intervalMs)
        }
    }
}
