package wtf.jobin.availability

import io.r2dbc.spi.ConnectionFactories
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.vendors.H2Dialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import wtf.jobin.db.ContentUuid
import wtf.jobin.worklet.AnnounceRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #124: the announce path joins the `hash(content_uuid)` swarm for every held Title, through the
 * [wtf.jobin.worklet.WorkletHypercore] seam — proven here with a fake seam (no process, no swarm).
 * This is the second "real proof" test: given held content, [AvailabilityService.announceHeldTitles]
 * calls swarmJoin with EXACTLY the frozen topics and nothing else. Pseudonymity is structural — the
 * service records no identity, only content-addressed topic joins.
 */
class AvailabilityServiceTest {

    /** A throwaway H2 handle solely to satisfy AnnounceRepository's ctor; the DB is never touched. */
    private fun dummyDb(): R2dbcDatabase = R2dbcDatabase.connect(
        connectionFactory = ConnectionFactories.get("r2dbc:h2:mem:///av_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1"),
        databaseConfig = R2dbcDatabaseConfig.Builder().also { it.explicitDialect = H2Dialect() },
    )

    private class FakeRepo(db: R2dbcDatabase, private val uuids: List<String>) : AnnounceRepository(db) {
        override suspend fun localContentUuids(): List<String> = uuids
    }

    private fun dashStripped(u: UUID) = u.toString().replace("-", "")

    @Test
    fun joinsFrozenTopicForEachHeldTitle() = runBlocking {
        val held = listOf(
            ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE),
            ContentUuid.forTmdb(603, ContentUuid.Kind.MOVIE),
        )
        val joins = mutableListOf<Pair<Long, String>>()
        val opens = AtomicInteger(0)
        val service = AvailabilityService(
            repo = FakeRepo(dummyDb(), held.map { dashStripped(it) }),
            openCore = { opens.incrementAndGet().toLong() },
            joinTopic = { handle, topic -> joins.add(handle to topic) },
        )

        val topics = service.announceHeldTitles()

        val expected = held.map { SwarmTopic.topicHex(it) }
        assertEquals(expected.toSet(), topics)
        assertEquals(expected.toSet(), joins.map { it.second }.toSet())
        // one core opened per title, and the handle it returned is the one joined.
        assertEquals(held.size, opens.get())
        assertEquals(held.size, joins.size)
        assertTrue(joins.all { it.first > 0 }, "each join used a real core handle")
    }

    @Test
    fun collapsesDuplicateAddressesToOneJoin() = runBlocking {
        val u = ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE)
        val joins = mutableListOf<String>()
        val opens = AtomicInteger(0)
        val service = AvailabilityService(
            repo = FakeRepo(dummyDb(), listOf(dashStripped(u), dashStripped(u))), // same title twice
            openCore = { opens.incrementAndGet().toLong() },
            joinTopic = { _, topic -> joins.add(topic) },
        )

        service.announceHeldTitles()

        assertEquals(listOf(SwarmTopic.topicHex(u)), joins) // joined once, not twice
        assertEquals(1, opens.get())
    }

    @Test
    fun holdingNothingJoinsNothing() = runBlocking {
        val opens = AtomicInteger(0)
        val service = AvailabilityService(
            repo = FakeRepo(dummyDb(), emptyList()),
            openCore = { opens.incrementAndGet().toLong() },
            joinTopic = { _, _ -> error("must not join when nothing is held") },
        )
        assertTrue(service.announceHeldTitles().isEmpty())
        assertEquals(0, opens.get()) // no worklet contact at all
    }
}
