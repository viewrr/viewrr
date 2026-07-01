package wtf.jobin.editorial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

private val log = LoggerFactory.getLogger("wtf.jobin.editorial.FeedFetcher")

/** One normalized entry from any feed, format-agnostic. */
data class FeedItem(
    val title: String,
    val url: String?,
    val summary: String?,      // short snippet, HTML stripped
    val content: String?,      // fuller body for the classifier, HTML stripped
    val publishedAt: Instant?,
)

// --- JSON Feed v1.1 (https://www.jsonfeed.org/version/1.1/) ---------------------------------------
@Serializable
private data class JsonFeedDoc(val items: List<JsonFeedItem> = emptyList())

@Serializable
private data class JsonFeedItem(
    val url: String? = null,
    @SerialName("external_url") val externalUrl: String? = null,
    val title: String? = null,
    val summary: String? = null,
    @SerialName("content_text") val contentText: String? = null,
    @SerialName("content_html") val contentHtml: String? = null,
    @SerialName("date_published") val datePublished: String? = null,
)

/**
 * Fetches and parses editorial feeds using only JDK + already-present deps:
 * HTTP via java.net.http.HttpClient (same as TmdbClient), JSON via kotlinx.serialization, XML via
 * javax.xml DOM. ZERO new gradle dependencies. Network errors are swallowed to an empty list so one
 * dead outlet never sinks a whole refresh.
 */
class FeedFetcher(
    private val http: HttpClient = HttpClient.newHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(feed: FeedConfig): List<FeedItem> {
        val body = try {
            withContext(Dispatchers.IO) {
                val req = HttpRequest.newBuilder(URI.create(feed.url))
                    .header("User-Agent", "viewrr-editorial/1.0") // some feeds 403 the default UA
                    .GET().build()
                val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() != 200) {
                    log.warn("editorial feed {} HTTP {}", feed.name, res.statusCode())
                    null
                } else {
                    res.body()
                }
            }
        } catch (e: Exception) {
            log.warn("editorial feed {} fetch failed: {}", feed.name, e.message)
            null
        } ?: return emptyList()

        return try {
            when (feed.format) {
                FeedFormat.json -> parseJsonFeed(body)
                FeedFormat.xml -> parseXmlFeed(body)
            }
        } catch (e: Exception) {
            log.warn("editorial feed {} parse failed: {}", feed.name, e.message)
            emptyList()
        }
    }

    fun parseJsonFeed(text: String): List<FeedItem> =
        json.decodeFromString<JsonFeedDoc>(text).items.mapNotNull { it ->
            val title = it.title?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            FeedItem(
                title = title,
                url = it.url ?: it.externalUrl,
                summary = stripHtml(it.summary ?: it.contentText ?: it.contentHtml),
                content = stripHtml(it.contentHtml ?: it.contentText ?: it.summary),
                publishedAt = parseInstant(it.datePublished),
            )
        }

    /** Handles RSS 2.0 (`<item>`) and Atom (`<entry>`) in one pass. */
    fun parseXmlFeed(text: String): List<FeedItem> {
        val doc = newSecureBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        doc.documentElement.normalize()
        val entries = buildList {
            addAll(doc.getElementsByTagName("item").asList())   // RSS
            addAll(doc.getElementsByTagName("entry").asList())  // Atom
        }
        return entries.mapNotNull { el ->
            val title = childText(el, "title")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val body = childText(el, "description")   // RSS
                ?: childText(el, "content")           // Atom / content:encoded
                ?: childText(el, "summary")           // Atom
            FeedItem(
                title = title,
                url = linkOf(el),
                summary = stripHtml(body)?.take(500),
                content = stripHtml(body),
                publishedAt = parseInstant(childText(el, "pubDate") ?: childText(el, "published") ?: childText(el, "updated")),
            )
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun newSecureBuilder() = DocumentBuilderFactory.newInstance().apply {
        // Harden against XXE: feeds are untrusted input.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isNamespaceAware = false
    }.newDocumentBuilder()

    /** RSS `<link>text</link>` vs Atom `<link href=".."/>`. Prefer rel=alternate for Atom. */
    private fun linkOf(el: Element): String? {
        val links = el.getElementsByTagName("link")
        for (i in 0 until links.length) {
            val n = links.item(i) as? Element ?: continue
            val href = n.getAttribute("href")
            if (href.isNotBlank()) {
                val rel = n.getAttribute("rel")
                if (rel.isBlank() || rel == "alternate") return href
            } else if (n.textContent.isNotBlank()) {
                return n.textContent.trim()
            }
        }
        return childText(el, "guid")
    }

    private fun childText(el: Element, tag: String): String? {
        val nodes = el.getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val t = nodes.item(i).textContent?.trim()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private fun org.w3c.dom.NodeList.asList(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }.filter { it.nodeType == Node.ELEMENT_NODE }
}

// --- shared, side-effect-free parsing utilities (also used by the classifier/matcher) ------------

/** Strip HTML tags + decode the handful of entities that show up in feed bodies. */
fun stripHtml(s: String?): String? {
    if (s == null) return null
    val noTags = s.replace(Regex("<[^>]+>"), " ")
    val decoded = noTags
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
    val collapsed = decoded.replace(Regex("\\s+"), " ").trim()
    return collapsed.ifBlank { null }
}

/** Lenient timestamp parse: RFC3339 (JSON Feed / Atom) then RFC1123 (RSS pubDate). null on failure. */
fun parseInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
}
