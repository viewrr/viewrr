package wtf.jobin.availability

import kotlinx.coroutines.runBlocking
import wtf.jobin.db.ContentUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * mesh-hub: the DHT lookup path — given a Title's content_uuid, [AvailabilityLookupService] derives
 * the EXACT SAME frozen topic [AvailabilityService.announceHeldTitles] joins as a provider, asks the
 * seam for a peer count, and returns it untouched. Proven with a fake seam (no process, no swarm),
 * same style as [AvailabilityServiceTest]. Pseudonymity is structural: [PeerAvailabilityRecord]
 * carries a count, never a peer identity.
 */
class AvailabilityLookupServiceTest {

    @Test
    fun looksUpTheFrozenTopicAndReturnsThePeerCountUntouched() = runBlocking {
        val uuid = ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE)
        val expectedTopic = SwarmTopic.topicHex(uuid)
        var seenTopic: String? = null
        var seenWaitMs: Long? = null
        val service = AvailabilityLookupService(
            lookupTopic = { topicHex, waitMs -> seenTopic = topicHex; seenWaitMs = waitMs; 3 },
            waitMs = 1500,
        )

        val record = service.lookup(uuid)

        assertEquals(expectedTopic, seenTopic, "lookup must use the same frozen topic the announcer joins")
        assertEquals(1500L, seenWaitMs)
        assertEquals(uuid, record.contentUuid)
        assertEquals(expectedTopic, record.topicHex)
        assertEquals(3, record.peersFound)
        assertNull(record.checkedAtEpochMs, "pure lookup result stamps no timestamp itself")
    }

    @Test
    fun noPeersFoundIsAValidZeroCountNotAnError() = runBlocking {
        val uuid = ContentUuid.forTmdb(603, ContentUuid.Kind.MOVIE)
        val service = AvailabilityLookupService(lookupTopic = { _, _ -> 0 })

        val record = service.lookup(uuid)

        assertEquals(0, record.peersFound)
    }
}
