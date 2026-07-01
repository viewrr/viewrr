package wtf.jobin.db

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ContentUuidTest {
    @Test
    fun deterministicForSameInput() {
        assertEquals(
            ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE),
            ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE),
        )
    }

    @Test
    fun movieAndTvWithSameIdDiffer() {
        // TMDB ids overlap across movie/TV, so kind MUST be part of the address.
        assertNotEquals(
            ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE),
            ContentUuid.forTmdb(550, ContentUuid.Kind.TV),
        )
    }

    @Test
    fun tvEpisodesAreDistinctAddresses() {
        val s1e1 = ContentUuid.forTmdb(1399, ContentUuid.Kind.TV, season = 1, episode = 1)
        val s1e2 = ContentUuid.forTmdb(1399, ContentUuid.Kind.TV, season = 1, episode = 2)
        val series = ContentUuid.forTmdb(1399, ContentUuid.Kind.TV)
        assertNotEquals(s1e1, s1e2)
        assertNotEquals(s1e1, series)
    }

    @Test
    fun isRfc4122Version5() {
        val u = ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE)
        assertEquals(5, u.version())      // name-based SHA-1
        assertEquals(2, u.variant())      // IETF variant
    }

    @Test
    fun frozenGoldenVector() {
        // Cross-repo contract: mobile/web MUST reproduce these exact values. If this fails, the
        // namespace, name format, or algorithm drifted — every peer's swarm key would break.
        assertEquals(
            UUID.fromString("bc592db3-805a-58ff-9f95-b90687681997"), // movie:550
            ContentUuid.forTmdb(550, ContentUuid.Kind.MOVIE),
        )
    }
}
