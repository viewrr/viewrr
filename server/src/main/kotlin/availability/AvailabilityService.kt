package wtf.jobin.availability

import org.slf4j.LoggerFactory
import wtf.jobin.worklet.AnnounceRepository
import wtf.jobin.worklet.WorkletHypercore

/**
 * #124 (P2P-ADR 0008): pseudonymous availability announcer.
 *
 * For every Title this deployment holds, join the swarm topic `hash(content_uuid)` as a provider so
 * peers can find it — WITHOUT ever recording who holds what. The service:
 *   1. asks [AnnounceRepository] which content_uuids the deployment can provide (reuse — #121),
 *   2. derives each swarm topic with [SwarmTopic] (the frozen `hash(content_uuid)` contract),
 *   3. opens a core and joins that topic through the existing [WorkletHypercore] seam.
 *
 * PSEUDONYMITY, by construction: this class persists NOTHING. There is no `publicKey <-> title`
 * table, no map of announced topics to identities. Membership of a topic swarm is the ONLY signal,
 * and that signal is content-addressed, not peer-addressed — exactly P2P-ADR 0008. (The topic hex
 * is one-way from content_uuid; joining it reveals "some peer has this content", never "peer X".)
 *
 * SEAM: like [wtf.jobin.worklet.WorkletAnnouncer], the two worklet calls are injected as lambdas so
 * the announce logic is unit-testable with a fake (no process, no swarm). Production binds them to a
 * real [WorkletHypercore] via [overHypercore] — reusing `coreOpen` + `swarmJoin`, editing neither.
 *
 * ponytail / simplifications, marked on purpose (this is the smallest honest slice):
 *  - One fresh writable core per Title, opened only to have a handle to join the topic with. Wiring
 *    that core to the Title's actual media bytes is a later slice (serve-bytes); until a peer serves,
 *    swarm membership is the availability signal and this is the correct amount of code.
 *  - Deployment-level, not per-node (inherited from [AnnounceRepository]).
 *  - No retry/interval loop here — [WorkletAnnouncer] already owns the periodic pass over the same
 *    repo. This service is the content-addressed *topic* path (coreOpen + swarmJoin); it is a
 *    capability, gated behind a running worklet, not started by default.
 */
class AvailabilityService(
    private val repo: AnnounceRepository,
    private val openCore: suspend () -> Long,
    private val joinTopic: suspend (handle: Long, topicHex: String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(AvailabilityService::class.java)

    /**
     * One announce pass: join the `hash(content_uuid)` swarm for every held Title. Returns the set
     * of topic hexes joined (also the natural assertion point for tests). Idempotent-ish: joining a
     * topic already joined is a no-op at the swarm layer.
     */
    suspend fun announceHeldTitles(): Set<String> {
        val topics = LinkedHashSet<String>()
        for (contentUuidHex in repo.localContentUuids()) {
            val topicHex = SwarmTopic.topicHexFromUuidHex(contentUuidHex)
            if (!topics.add(topicHex)) continue // collapse duplicate addresses to one join
            val handle = openCore()
            joinTopic(handle, topicHex)
        }
        if (topics.isNotEmpty()) log.info("availability: joined {} content topic(s)", topics.size)
        return topics
    }

    companion object {
        /**
         * Bind the seam to a live [WorkletHypercore], reusing its `coreOpen` + `swarmJoin`. A fresh
         * writable core is opened per announce; only its handle is used (see class note).
         */
        fun overHypercore(repo: AnnounceRepository, hypercore: WorkletHypercore): AvailabilityService =
            AvailabilityService(
                repo = repo,
                openCore = { hypercore.coreOpen().handle },
                joinTopic = { handle, topicHex -> hypercore.swarmJoin(handle, topicHex) },
            )
    }
}
