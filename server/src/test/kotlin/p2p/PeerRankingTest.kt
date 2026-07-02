package wtf.jobin.p2p

import kotlin.test.Test
import kotlin.test.assertEquals

/** #125: pure Plus Code proximity + uplink ranking. No network, no swarm — stdlib only. */
class PeerRankingTest {
    // Requester near "8FVC9G8F". Codes share more leading chars the closer they are.
    private val requester = "8FVC9G8F+6W"

    @Test
    fun nearerPrefixRanksFirst() {
        val far = CandidatePeer("far", "8FXX0000+00", 100.0) // shares "8F"
        val mid = CandidatePeer("mid", "8FVC0000+00", 100.0) // shares "8FVC"
        val near = CandidatePeer("near", "8FVC9G8F+00", 1.0) // shares "8FVC9G8F", slow uplink
        val ranked = rankPeers(requester, listOf(far, mid, near))
        // Proximity dominates uplink: near first despite slowest link.
        assertEquals(listOf("near", "mid", "far"), ranked.map { it.id })
    }

    @Test
    fun equalProximityFasterUplinkWins() {
        val slow = CandidatePeer("slow", "8FVC9G8F+11", 50.0)
        val fast = CandidatePeer("fast", "8FVC9G8F+22", 500.0)
        val ranked = rankPeers(requester, listOf(slow, fast))
        assertEquals(listOf("fast", "slow"), ranked.map { it.id })
    }

    @Test
    fun missingCodeSortsLastAfterAnyCodedPeer() {
        val coded = CandidatePeer("coded", "8FXX0000+00", 1.0) // shares only "8F", slow
        val noCode = CandidatePeer("noCode", null, 9999.0) // huge uplink but no code
        val ranked = rankPeers(requester, listOf(noCode, coded))
        // Coded peer wins even with a 1 Mbps link vs a codeless 9.9 Gbps peer.
        assertEquals(listOf("coded", "noCode"), ranked.map { it.id })
    }

    @Test
    fun missingUplinkSortsLastWithinSameProximity() {
        val withUplink = CandidatePeer("withUplink", "8FVC9G8F+11", 10.0)
        val noUplink = CandidatePeer("noUplink", "8FVC9G8F+22", null)
        val ranked = rankPeers(requester, listOf(noUplink, withUplink))
        assertEquals(listOf("withUplink", "noUplink"), ranked.map { it.id })
    }

    @Test
    fun fullyMissingPeersOrderDeterministicallyById() {
        // Same proximity (none) and same uplink (none) → stable id ascending, input order ignored.
        val b = CandidatePeer("b", null, null)
        val a = CandidatePeer("a", null, null)
        val c = CandidatePeer("c", null, null)
        val ranked = rankPeers(requester, listOf(b, c, a))
        assertEquals(listOf("a", "b", "c"), ranked.map { it.id })
    }

    @Test
    fun emptyListReturnsEmpty() {
        assertEquals(emptyList(), rankPeers(requester, emptyList()))
    }

    @Test
    fun combinedTiersRankCorrectly() {
        val near = CandidatePeer("near", "8FVC9G8F+AA", 5.0)
        val nearFaster = CandidatePeer("nearFaster", "8FVC9G8F+BB", 50.0)
        val midCode = CandidatePeer("midCode", "8FVC0000+00", 900.0)
        val codeless = CandidatePeer("codeless", null, 900.0)
        val ranked = rankPeers(requester, listOf(codeless, near, midCode, nearFaster))
        assertEquals(listOf("nearFaster", "near", "midCode", "codeless"), ranked.map { it.id })
    }
}
