package wtf.jobin.p2p

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #125: selection layer over the pure ranker — primary + ordered fallback chain. */
class PeerSelectionServiceTest {
    private val service = PeerSelectionService()

    // Requester near "8FVC9G8F" (same fixture shape as PeerRankingTest).
    private val requester = "8FVC9G8F+6W"

    @Test
    fun topRankedIsPrimaryRestAreOrderedFallbacks() {
        val far = CandidatePeer("far", "8FXX0000+00", 100.0) // shares "8F"
        val mid = CandidatePeer("mid", "8FVC0000+00", 100.0) // shares "8FVC"
        val near = CandidatePeer("near", "8FVC9G8F+00", 1.0) // shares "8FVC9G8F"

        val selection = service.select(requester, listOf(far, mid, near))!!

        // Proximity dominates: near is primary; fallbacks follow in rank order.
        assertEquals("near", selection.primary.id)
        assertEquals(listOf("mid", "far"), selection.fallbacks.map { it.id })
        // Convenience chain is primary-first, full order.
        assertEquals(listOf("near", "mid", "far"), selection.chain.map { it.id })
    }

    @Test
    fun emptyCandidatesYieldNoSelection() {
        assertNull(service.select(requester, emptyList()))
    }

    @Test
    fun droppedPrimaryFallsThroughToNext() {
        val far = CandidatePeer("far", "8FXX0000+00", 100.0)
        val mid = CandidatePeer("mid", "8FVC0000+00", 100.0)
        val near = CandidatePeer("near", "8FVC9G8F+00", 1.0)

        val first = service.select(requester, listOf(far, mid, near))!!
        assertEquals("near", first.primary.id)

        // Primary unreachable → promote next-best, chain shrinks by one.
        val second = first.dropPrimary()!!
        assertEquals("mid", second.primary.id)
        assertEquals(listOf("far"), second.fallbacks.map { it.id })

        // Drop again → last peer becomes primary, no fallbacks left.
        val third = second.dropPrimary()!!
        assertEquals("far", third.primary.id)
        assertEquals(emptyList(), third.fallbacks.map { it.id })

        // Exhausted chain → nothing left to try.
        assertNull(third.dropPrimary())
    }
}
