package wtf.jobin.p2p

/**
 * #125 (P2P) Peer selection: Plus Code proximity + uplink.
 *
 * PURE, UNWIRED ranking logic. The swarm/DHT layer (#121 Hyper*) does not exist yet,
 * so this only implements the part that is real and independent: given a requester's
 * Plus Code and a candidate list, rank candidates nearest-first then fastest-uplink.
 * The swarm will feed it a candidate list later; this is wired to nothing.
 *
 * NOTE: intentionally separate from the shipped egress-IP locality path (#79). That
 * remains untouched; integration/replacement happens once the swarm exists.
 */

/** A candidate peer the swarm would hand us. plusCode / uplinkMbps may be absent. */
data class CandidatePeer(
    val id: String,
    val plusCode: String? = null,
    val uplinkMbps: Double? = null,
)

/**
 * Rank [candidates] for the peer at [requesterPlusCode].
 *
 * Order:
 *   1. Peers WITH a Plus Code before peers without (missing code sorts last).
 *   2. Nearest by Plus Code first — measured by shared leading-character count.
 *   3. Faster uplink wins (missing uplink sorts last within a proximity tier).
 *   4. Peer id ascending — a stable, deterministic final tiebreak.
 *
 * ponytail: proximity is a HEURISTIC — shared Plus Code prefix length. Open Location
 * Code prefixes share leading chars for nearby locations (each pair of chars refines
 * the grid ~20x), so "longer shared prefix ≈ physically closer" holds well enough to
 * RANK. Ceiling: it is not a real distance — it ignores grid wraparound at cell edges
 * (two adjacent points straddling a boundary can share a short prefix) and treats all
 * refinement levels linearly. Swap to a true OLC decode + haversine ONLY if ranking
 * quality demands it; for peer ordering the prefix count is sufficient and adds zero deps.
 */
fun rankPeers(requesterPlusCode: String, candidates: List<CandidatePeer>): List<CandidatePeer> {
    val requester = normalize(requesterPlusCode)
    return candidates.sortedWith(
        compareByDescending<CandidatePeer> { it.plusCode != null } // coded peers first
            .thenByDescending { sharedPrefixLength(requester, normalize(it.plusCode)) }
            .thenByDescending { it.uplinkMbps ?: Double.NEGATIVE_INFINITY } // no uplink = last
            .thenBy { it.id }, // deterministic regardless of input order
    )
}

/**
 * ponytail: normalization is deliberately minimal — uppercase and drop the OLC '+'
 * separator so codes of differing precision compare on their leading grid chars.
 * We do not validate or decode; a malformed/blank code just yields a short shared prefix.
 */
private fun normalize(code: String?): String =
    code?.uppercase()?.replace("+", "") ?: ""

/** Count of matching leading characters between two normalized Plus Codes. */
private fun sharedPrefixLength(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}
