package wtf.jobin.editorial

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("wtf.jobin.editorial.EditorialFeeds")

/** Wire shape of one entry in resources/editorial/feeds.json. */
@Serializable
data class FeedConfig(
    val name: String,
    val url: String,
    val format: FeedFormat,
    val type: FeedType = FeedType.reviews,
    val enabled: Boolean = false,
)

@Suppress("EnumEntryName") // lower-case entries so JSON stays human-friendly (no @SerialName per value)
@Serializable
enum class FeedFormat { json, xml }

@Suppress("EnumEntryName")
@Serializable
enum class FeedType { reviews, awards, festival }

@Serializable
private data class FeedRegistry(
    @SerialName("feeds") val feeds: List<FeedConfig> = emptyList(),
)

/**
 * Loads the editorial feed registry from a classpath JSON resource. Config over code: an outlet is
 * added by editing feeds.json, never by touching Kotlin. Only enabled feeds are returned.
 *
 * ponytail: classpath-only for now (bundled with the jar). If ops ever need to add outlets without a
 * redeploy, add a filesystem override path here — not a new dep.
 */
object EditorialFeeds {
    private val json = Json { ignoreUnknownKeys = true } // tolerate _comment / note keys

    const val DEFAULT_RESOURCE = "editorial/feeds.json"

    fun load(resource: String = DEFAULT_RESOURCE): List<FeedConfig> {
        val stream = javaClass.classLoader.getResourceAsStream(resource)
        if (stream == null) {
            log.warn("editorial feed registry not found on classpath: {}", resource)
            return emptyList()
        }
        val text = stream.bufferedReader().use { it.readText() }
        return parse(text)
    }

    /** Split out for unit testing without a classpath round-trip. Returns only enabled feeds. */
    fun parse(text: String): List<FeedConfig> {
        val all = json.decodeFromString<FeedRegistry>(text).feeds
        val enabled = all.filter { it.enabled }
        log.info("editorial: {} feed(s) enabled of {} configured", enabled.size, all.size)
        return enabled
    }
}
