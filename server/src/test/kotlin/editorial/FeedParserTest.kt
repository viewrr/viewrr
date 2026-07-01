package wtf.jobin.editorial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeedParserTest {
    private val fetcher = FeedFetcher()

    @Test fun parsesJsonFeedV11() {
        val body = """
            {
              "version": "https://jsonfeed.org/version/1.1",
              "title": "The Hollywood Reporter",
              "items": [
                {
                  "title": "'Dune' Review",
                  "url": "https://thr.example/dune",
                  "summary": "<p>Great &amp; epic.</p>",
                  "date_published": "2024-03-01T10:00:00Z"
                },
                { "title": "", "url": "https://thr.example/blank" }
              ]
            }
        """.trimIndent()
        val items = fetcher.parseJsonFeed(body)
        assertEquals(1, items.size) // blank-title item dropped
        val it = items.first()
        assertEquals("'Dune' Review", it.title)
        assertEquals("https://thr.example/dune", it.url)
        assertEquals("Great & epic.", it.summary) // tags stripped, entity decoded
        assertNotNull(it.publishedAt)
    }

    @Test fun parsesRss20() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>RogerEbert.com</title>
                <item>
                  <title>Oppenheimer review</title>
                  <link>https://ebert.example/oppenheimer</link>
                  <description>&lt;b&gt;A bomb of a film.&lt;/b&gt;</description>
                  <pubDate>Wed, 02 Jul 2025 10:00:00 GMT</pubDate>
                </item>
              </channel>
            </rss>
        """.trimIndent()
        val items = fetcher.parseXmlFeed(body)
        assertEquals(1, items.size)
        val it = items.first()
        assertEquals("Oppenheimer review", it.title)
        assertEquals("https://ebert.example/oppenheimer", it.url)
        assertEquals("A bomb of a film.", it.summary)
        assertNotNull(it.publishedAt) // RFC1123 pubDate parsed
    }

    @Test fun parsesAtomLinkHref() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Film Companion</title>
              <entry>
                <title>'Poor Things' Review</title>
                <link rel="alternate" href="https://fc.example/poor-things"/>
                <summary>A bold fantasia.</summary>
                <updated>2024-01-15T08:30:00Z</updated>
              </entry>
            </feed>
        """.trimIndent()
        val items = fetcher.parseXmlFeed(body)
        assertEquals(1, items.size)
        assertEquals("https://fc.example/poor-things", items.first().url)
    }
}
