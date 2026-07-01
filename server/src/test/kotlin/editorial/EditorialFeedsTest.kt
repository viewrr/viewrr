package wtf.jobin.editorial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorialFeedsTest {
    @Test fun bundledRegistryParsesAndFiltersEnabled() {
        val feeds = EditorialFeeds.load() // reads resources/editorial/feeds.json off the classpath
        assertTrue(feeds.isNotEmpty(), "expected at least one enabled feed")
        assertTrue(feeds.all { it.enabled }, "load() must return only enabled feeds")
        // Lensman has no known URL and must stay disabled (flagged, not guessed).
        assertFalse(feeds.any { it.name == "Lensman" })
        // The known-good HTR JSON feed is enabled.
        assertTrue(feeds.any { it.name.contains("Hollywood Reporter") && it.format == FeedFormat.json })
    }

    @Test fun toleratesCommentAndNoteKeys() {
        val text = """
            { "_comment": "hi",
              "feeds": [ { "name": "X", "url": "https://x", "format": "xml", "type": "reviews", "enabled": true, "note": "n" } ] }
        """.trimIndent()
        val feeds = EditorialFeeds.parse(text)
        assertEquals(1, feeds.size)
        assertEquals(FeedType.reviews, feeds.first().type)
    }
}
