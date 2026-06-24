package wtf.jobin.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbClientTest {
    private val client = TmdbClient(apiKey = "test")

    @Test
    fun parsesFirstResultToAbsoluteUrls() {
        val body = """
            {"results":[
              {"id":603,"poster_path":"/p.jpg","backdrop_path":"/b.jpg","overview":"Neo."},
              {"id":604,"poster_path":"/x.jpg"}
            ]}
        """.trimIndent()
        val m = client.parse(body)!!
        assertEquals(603, m.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w500/p.jpg", m.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", m.backdrop)
        assertEquals("Neo.", m.overview)
    }

    @Test
    fun nullPathsAndBlankOverviewStayNull() {
        val m = client.parse("""{"results":[{"id":1,"overview":""}]}""")!!
        assertEquals(1, m.tmdbId)
        assertNull(m.poster)
        assertNull(m.backdrop)
        assertNull(m.overview)
    }

    @Test
    fun emptyResultsIsNull() {
        assertNull(client.parse("""{"results":[]}"""))
    }

    @Test
    fun disabledWhenKeyBlank() {
        assertEquals(false, TmdbClient(apiKey = "").enabled)
    }
}
