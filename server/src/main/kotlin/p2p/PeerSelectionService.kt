package wtf.jobin.p2p

/**
 * #125 (P2P) Peer SELECTION — the wiring layer directly on top of the pure ranker
 * [rankPeers] (PeerRanking.kt, #148). Turns a ranked list into an actionable choice:
 * one primary peer plus an ordered fallback chain to try when the primary drops.
 *
 * ponytail: this is the SMALLEST thing over the ranker. It adds no scoring of its own —
 * rank order IS selection order. Primary = rank[0]; fallbacks = the rest, in rank order.
 *
 * FLAGGED (not built here): the candidate list is an INPUT. Its real source is
 * swarm-discovered peers carrying their Plus Code + measured uplink (#124 availability,
 * inc-2 swarm, #121 Hyper*). That discovery + measurement wiring is a later increment;
 * this layer only decides *which* candidate to use once someone hands us the list.
 */

/**
 * The outcome of a selection: the peer to use now and the ordered peers to fall back to.
 *
 * ponytail: kept as a plain immutable value — no connection state, no health tracking.
 * "Dropping" a primary is modeled as producing a NEW selection ([dropPrimary]); the
 * caller owns liveness. That is enough to express a fallback chain without a state machine.
 */
data class PeerSelection(
    val primary: CandidatePeer,
    val fallbacks: List<CandidatePeer>,
) {
    /** Full ordered attempt chain, primary first. Convenience for callers that iterate. */
    val chain: List<CandidatePeer>
        get() = buildList {
            add(primary)
            addAll(fallbacks)
        }

    /**
     * Primary failed (unreachable / dropped): promote the next-best fallback.
     * Returns null when the chain is exhausted — no peer left to try.
     */
    fun dropPrimary(): PeerSelection? =
        fallbacks.firstOrNull()?.let { next -> PeerSelection(next, fallbacks.drop(1)) }
}

/**
 * Selects a peer (and its fallback chain) for a requester from a candidate list.
 *
 * ponytail: stateless, zero-dependency class. Modeled as a service (not a bare function)
 * so the later swarm candidate-source can be injected here without changing call sites.
 * Today it is a thin adapter over [rankPeers].
 */
class PeerSelectionService {
    /**
     * Rank [candidates] for [requesterPlusCode] and pick the best as primary, the rest
     * as an ordered fallback chain. Empty candidate list → null (nothing to select).
     */
    fun select(requesterPlusCode: String, candidates: List<CandidatePeer>): PeerSelection? {
        val ranked = rankPeers(requesterPlusCode, candidates)
        val primary = ranked.firstOrNull() ?: return null // empty candidates: no selection
        return PeerSelection(primary, ranked.drop(1))
    }
}
