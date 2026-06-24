package wtf.jobin.stremio

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.* // #77: receiveNullable for the optional profile body
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import wtf.jobin.config.AppConfig
import wtf.jobin.streaming.SubtitleExtractor
import java.util.UUID

private val stJson = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = true }

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.respondStremio(body: T) {
    // Stremio web is a separate origin; addons must allow cross-origin reads.
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    respondText(stJson.encodeToString(body), ContentType.Application.Json)
}

fun Route.stremioRoutes(db: R2dbcDatabase, media: AppConfig.Media, publicBaseUrl: String, keys: StremioKeys) {
    val svc = StremioService(db, publicBaseUrl)
    val subs = SubtitleExtractor(media.ffmpegPath, media.ffprobePath, media.hlsRoot, db)

    // Authenticated: hand the logged-in user their addon install URL.
    authenticate("auth-jwt") {
        post("/me/stremio-key") {
            val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
            // #77: optional capability profile in the body. No body / empty body / unparseable
            // → null = the default no-profile key (today's behavior). receiveNullable returns
            // null on an empty body; the catch covers malformed/non-JSON payloads.
            val profile = runCatching { call.receiveNullable<CapabilityProfile>() }.getOrNull()
            val key = keys.keyFor(uid, profile)
            call.respond(
                mapOf(
                    "key" to key,
                    "installUrl" to "$publicBaseUrl/stremio/$key/manifest.json",
                ),
            )
        }
    }

    // Public addon surface — the key in the path is the credential (resolves to a user).
    route("/stremio/{key}") {
        get("manifest.json") {
            keys.resolve(call.parameters["key"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondStremio(svc.manifest())
        }

        get("catalog/{type}/{id}") {
            val uid = keys.resolve(call.parameters["key"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val type = call.parameters["type"]!!
            call.respondStremio(StCatalogResponse(catalog(svc, uid, type, null)))
        }
        get("catalog/{type}/{id}/{extra}") {
            val uid = keys.resolve(call.parameters["key"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val type = call.parameters["type"]!!
            val extra = call.parameters["extra"]!!.removeSuffix(".json")
            val search = extra.split("&").firstOrNull { it.startsWith("search=") }?.removePrefix("search=")
            call.respondStremio(StCatalogResponse(catalog(svc, uid, type, search)))
        }

        get("meta/{type}/{id}") {
            val uid = keys.resolve(call.parameters["key"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            val type = call.parameters["type"]!!
            val id = call.parameters["id"]!!.removeSuffix(".json")
            val meta = svc.meta(uid, type, id) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondStremio(StMetaResponse(meta))
        }

        get("stream/{type}/{id}") {
            val key = call.parameters["key"]!!
            val uid = keys.resolve(key) ?: return@get call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["id"]!!.removeSuffix(".json")
            call.respondStremio(StStreamResponse(svc.streams(uid, key, id)))
        }

        get("subtitles/{type}/{id}") {
            val key = call.parameters["key"]!!
            val uid = keys.resolve(key) ?: return@get call.respond(HttpStatusCode.NotFound)
            val id = call.parameters["id"]!!.removeSuffix(".json")
            val mediaId = svc.mediaIdFor(uid, id) ?: return@get call.respondStremio(StSubtitleResponse(emptyList()))
            val tracks = subs.tracks(mediaId).map {
                StSubtitle(id = "vsub-${it.index}", url = "$publicBaseUrl${it.url}?key=$key", lang = it.lang ?: "und")
            }
            call.respondStremio(StSubtitleResponse(tracks))
        }
    }
}

private suspend fun catalog(svc: StremioService, uid: UUID, type: String, search: String?) = when (type) {
    "movie" -> svc.movieCatalog(uid, search)
    "series" -> svc.seriesCatalog(uid, search)
    else -> emptyList()
}
