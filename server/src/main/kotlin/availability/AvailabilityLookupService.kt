package wtf.jobin.availability

import org.slf4j.LoggerFactory
import wtf.jobin.worklet.WorkletHypercore
import java.util.UUID

/**
 * mesh-hub (P2P-ADR 0008/0014): the read-only counterpart to [AvailabilityService]. Where
 * [AvailabilityService] ANNOUNCES ("I hold this content_uuid, join my topic"), this service LOOKS
 * UP ("does the mesh have ANY provider for this content_uuid at all"). Same content-addressed
 * topic ([SwarmTopic]), same pseudonymity contract: the result is a PRESENCE COUNT, never a peer
 * identity — there is still no `publicKey <-> title` table anywhere. This is the Hub asking a
 * yes/no-ish availability question of the mesh, e.g. for an acquisition/health-check workflow
 * deciding whether content is already reachable P2P before falling back to another source.
 *
 * [PeerAvailabilityRecord] is intentionally the ONLY thing this service returns or could persist:
 * a content address, its topic, a peer count, and when the check ran. Callers that want to persist
 * it get exactly that shape and nothing more identity-bearing.
 *
 * SEAM: like [AvailabilityService], the worklet call is injected as a lambda so the lookup logic
 * is unit-testable with a fake (no process, no swarm). Production binds it to a real
 * [WorkletHypercore] via [overHypercore].
 */
class AvailabilityLookupService(
    private val lookupTopic: suspend (topicHex: String, waitMs: Long) -> Int,
    private val waitMs: Long = 3000,
) {
    private val log = LoggerFactory.getLogger(AvailabilityLookupService::class.java)

    /** Look up mesh availability for one Title's content address. Persists nothing itself. */
    suspend fun lookup(contentUuid: UUID): PeerAvailabilityRecord {
        val topicHex = SwarmTopic.topicHex(contentUuid)
        val peersFound = lookupTopic(topicHex, waitMs)
        log.debug("availability lookup: topic {} -> {} peer(s)", topicHex, peersFound)
        return PeerAvailabilityRecord(
            contentUuid = contentUuid,
            topicHex = topicHex,
            peersFound = peersFound,
        )
    }

    companion object {
        /** Bind the seam to a live [WorkletHypercore], reusing its `swarmLookup` op. */
        fun overHypercore(hypercore: WorkletHypercore, waitMs: Long = 3000): AvailabilityLookupService =
            AvailabilityLookupService(
                lookupTopic = { topicHex, wait -> hypercore.swarmLookup(topicHex, wait).peersFound },
                waitMs = waitMs,
            )
    }
}

/**
 * A pseudonymous availability snapshot for one Title's content address — a presence COUNT, never a
 * peer identity or list. [checkedAtEpochMs] is null in the pure result path; callers that persist
 * this stamp it themselves (kept out of this class so the service stays a pure function of its
 * seam, matching [AvailabilityService]'s no-persistence design).
 */
data class PeerAvailabilityRecord(
    val contentUuid: UUID,
    val topicHex: String,
    val peersFound: Int,
    val checkedAtEpochMs: Long? = null,
)
