package wtf.jobin.availability

import wtf.jobin.db.ContentUuid
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #124: the deterministic `hash(content_uuid)` topic derivation is the load-bearing cross-repo
 * contract. If any of these move, the mobile/web peers and the `worklet/topic.mjs` provider join a
 * DIFFERENT swarm and availability silently breaks. These are the "real proof" tests that need no
 * running worklet.
 */
class SwarmTopicTest {

    // The frozen golden pair, copied verbatim from worklet/topic.mjs.
    private val goldenUuid = "bc592db3-805a-58ff-9f95-b90687681997"
    private val goldenTopic = "a4f704e6350a26c2910080d281a6097163a86aa7f19f4921fc10f7bac0df1643"

    @Test
    fun blake2bMatchesKnownVector() {
        // Independent guard on the hand-rolled BLAKE2b: the standard empty-input BLAKE2b-256 vector.
        // If this fails the digest itself is wrong, before any UUID logic is involved.
        val empty = Blake2b256.hash(ByteArray(0))
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            empty.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun matchesFrozenGoldenVector() {
        assertEquals(goldenTopic, SwarmTopic.topicHex(UUID.fromString(goldenUuid)))
    }

    @Test
    fun hexFormAcceptsDashedAndUndashed() {
        assertEquals(goldenTopic, SwarmTopic.topicHexFromUuidHex(goldenUuid))
        assertEquals(goldenTopic, SwarmTopic.topicHexFromUuidHex(goldenUuid.replace("-", "")))
        assertEquals(goldenTopic, SwarmTopic.topicHexFromUuidHex(goldenUuid.uppercase()))
    }

    @Test
    fun chainsFromContentUuidOfMovie550() {
        // ContentUuid.forTmdb(550, MOVIE) == the golden UUID (see ContentUuidTest.frozenGoldenVector),
        // so the whole DB-address -> swarm-topic path reproduces the frozen topic end to end.
        assertEquals(goldenTopic, SwarmTopic.topicHex(ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE)))
    }

    @Test
    fun isDeterministic() {
        val u = ContentUuid.forTmdb(603, ContentUuid.Kind.MOVIE)
        assertEquals(SwarmTopic.topicHex(u), SwarmTopic.topicHex(u))
    }

    @Test
    fun distinctContentDistinctTopic() {
        assertNotEquals(
            SwarmTopic.topicHex(ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE)),
            SwarmTopic.topicHex(ContentUuid.forTmdb(603, ContentUuid.Kind.MOVIE)),
        )
    }

    @Test
    fun topicIs32ByteLowercaseHex() {
        val t = SwarmTopic.topicHex(ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE))
        assertEquals(64, t.length) // 32 bytes
        assertTrue(t.all { it in "0123456789abcdef" }, "must be lowercase hex")
    }

    @Test
    fun rejectsWrongLengthContentUuidHex() {
        assertFailsWith<IllegalArgumentException> { SwarmTopic.topicHexFromUuidHex("deadbeef") }
    }
}
