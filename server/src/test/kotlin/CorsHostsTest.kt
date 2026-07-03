package wtf.jobin.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CorsHostsTest {

    @Test
    fun bareHostIsHttpOnly() {
        // Dev localhost entries keep Ktor's http default (unchanged pre-#118 behavior).
        assertEquals(CorsHost("localhost:5173", listOf("http")), parseCorsHost("localhost:5173"))
    }

    @Test
    fun schemeQualifiedOriginKeepsItsScheme() {
        // The prod client sends "https://app.viewrr.stream" as its Origin — must match on https.
        assertEquals(
            CorsHost("app.viewrr.stream", listOf("https")),
            parseCorsHost("https://app.viewrr.stream"),
        )
    }

    @Test
    fun trailingSlashAndWhitespaceStripped() {
        assertEquals(
            CorsHost("app.viewrr.stream", listOf("https")),
            parseCorsHost("  https://app.viewrr.stream/  "),
        )
    }

    @Test
    fun schemeIsLowercased() {
        assertEquals(
            CorsHost("app.viewrr.stream", listOf("https")),
            parseCorsHost("HTTPS://app.viewrr.stream"),
        )
    }

    @Test
    fun blankAndMalformedDropped() {
        assertNull(parseCorsHost("   "))
        assertNull(parseCorsHost(""))
        assertNull(parseCorsHost("https://"))
    }

    @Test
    fun resolveMixedListDedupesAndDropsBlanks() {
        val resolved = resolveCorsHosts(
            listOf(
                "localhost:5173",
                "https://app.viewrr.stream",
                "https://app.viewrr.stream", // duplicate
                "",
            ),
        )
        assertEquals(
            listOf(
                CorsHost("localhost:5173", listOf("http")),
                CorsHost("app.viewrr.stream", listOf("https")),
            ),
            resolved,
        )
    }
}
