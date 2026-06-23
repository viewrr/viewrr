package wtf.jobin.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

private val log = LoggerFactory.getLogger("wtf.jobin.scanner.TmdbClient")

/** Enriched movie metadata. Image fields are absolute URLs, ready to serve to Stremio. */
data class TmdbMeta(
    val tmdbId: Int,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
)

@Serializable
private data class TmdbSearchResponse(val results: List<TmdbResult> = emptyList())

@Serializable
private data class TmdbResult(
    val id: Int,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
)

/**
 * Minimal TMDb movie lookup over the JDK HttpClient (no new gradle dep, no ktor-client).
 * Disabled when [apiKey] is blank — [lookupMovie] then returns null and the scan proceeds
 * with filename-only metadata.
 */
class TmdbClient(
    private val apiKey: String,
    private val http: HttpClient = HttpClient.newHttpClient(),
    // ponytail: image base + sizes hardcoded; make config if you ever proxy/self-host images.
    private val imageBase: String = "https://image.tmdb.org/t/p",
) {
    private val json = Json { ignoreUnknownKeys = true }
    val enabled: Boolean get() = apiKey.isNotBlank()

    suspend fun lookupMovie(title: String, year: Int?): TmdbMeta? {
        if (!enabled || title.isBlank()) return null
        val q = URLEncoder.encode(title, StandardCharsets.UTF_8)
        val yearParam = year?.let { "&year=$it" } ?: ""
        val url = "https://api.themoviedb.org/3/search/movie?api_key=$apiKey&query=$q$yearParam"
        return runCatching {
            val res = withContext(Dispatchers.IO) {
                http.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
            if (res.statusCode() != 200) {
                log.warn("TMDb search '{}' ({}) -> HTTP {}", title, year, res.statusCode())
                return null
            }
            parse(res.body())
        }.onFailure { log.warn("TMDb lookup failed for '{}'", title, it) }.getOrNull()
    }

    /** Pure: first result of a TMDb search-movie JSON body -> [TmdbMeta], or null if no results. */
    fun parse(body: String): TmdbMeta? {
        val r = json.decodeFromString<TmdbSearchResponse>(body).results.firstOrNull() ?: return null
        return TmdbMeta(
            tmdbId = r.id,
            poster = r.posterPath?.let { "$imageBase/w500$it" },
            backdrop = r.backdropPath?.let { "$imageBase/w1280$it" },
            overview = r.overview?.ifBlank { null },
        )
    }
}
