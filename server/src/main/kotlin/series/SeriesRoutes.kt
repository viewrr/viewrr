package wtf.jobin.series

import io.ktor.server.auth.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

@Serializable
data class ShowView(val showTitle: String, val episodeCount: Int, val seasonCount: Int)

@Serializable
data class EpisodeView(
    val mediaId: String,
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val hlsPath: String?,
)

@Serializable
data class SeasonView(val season: Int?, val episodes: List<EpisodeView>)

@Serializable
data class SeriesDetailView(val showTitle: String, val seasons: List<SeasonView>)

fun Route.seriesRoutes(db: R2dbcDatabase) {
    val repo = SeriesRepository(db)
    authenticate("auth-jwt") {
        get("/series") {
            call.respond(
                repo.shows().map { ShowView(it.showTitle, it.episodeCount, it.seasonCount) },
            )
        }
        get("/series/{showTitle}") {
            // Ktor auto-url-decodes the path param.
            val name = call.parameters["showTitle"]!!
            val episodes = repo.episodes(name)
            if (episodes.isEmpty()) throw NotFoundException()
            val seasons = episodes
                .groupBy { it.seasonNumber }
                .map { (season, eps) ->
                    SeasonView(
                        season = season,
                        episodes = eps.map { ep ->
                            EpisodeView(
                                mediaId = ep.mediaId.toString(),
                                title = ep.title,
                                seasonNumber = ep.seasonNumber,
                                episodeNumber = ep.episodeNumber,
                                hlsPath = ep.hlsPath,
                            )
                        },
                    )
                }
                .sortedWith(compareBy(nullsLast()) { it.season })
            call.respond(SeriesDetailView(name, seasons))
        }
    }
}
