package wtf.jobin.streaming

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch // #94: fire-and-forget next-episode prefetch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.config.AppConfig
import wtf.jobin.db.MediaItems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

// HLS playlist + MPEG-TS segment content types — neither has a builtin in ContentType.
private val M3U8_CT = ContentType.parse("application/vnd.apple.mpegurl")
private val TS_CT = ContentType.parse("video/mp2t")

fun Route.streamRoutes(
    db: R2dbcDatabase,
    media: AppConfig.Media,
    stremioKeys: wtf.jobin.stremio.StremioKeys,
    coordinator: wtf.jobin.scanner.TranscodeCoordinator,
) {
    val hlsRoot = Path.of(media.hlsRoot)
    route("/stream") {
        // PartialContent gives us Range support transparently; LocalFileContent on Netty
        // still serves both full and ranged responses via DefaultFileRegion (zero-copy).
        install(PartialContent)
        // Stremio/HLS: key as a path PREFIX so the playlist's relative segment + variant URIs
        // (seg_000.ts, v0.m3u8) resolve under it. HLS players drop ?query on relative refs.
        get("/k/{key}/{media_id}/{file}") {
            val key = call.parameters["key"] ?: throw NotFoundException()
            val uid = stremioKeys.resolve(key) ?: throw NotFoundException()
            // #77/#78: the key carries the device's capability profile (null = default dir).
            val profile = stremioKeys.resolveProfile(key)
            serveHlsFile(call, db, hlsRoot, uid, coordinator, profile)
        }
        // optional JWT: browser/app clients send Bearer; single-file fetches may pass ?key=
        authenticate("auth-jwt", optional = true) {
            get("/{media_id}/{file}") {
                // #78: a JWT-only client has no key → no profile → "default" dir (today's path).
                // A ?key= client uses that key's profile, so its segment requests hit the same dir
                // the player's playlist was served from.
                val jwtUid = call.principal<JWTPrincipal>()?.subject?.let { UUID.fromString(it) }
                val keyParam = call.request.queryParameters["key"]
                val uid = jwtUid
                    ?: keyParam?.let { stremioKeys.resolve(it) }
                    ?: throw NotFoundException()
                val profile = if (jwtUid == null) keyParam?.let { stremioKeys.resolveProfile(it) } else null
                serveHlsFile(call, db, hlsRoot, uid, coordinator, profile)
            }
        }
    }
}

// Serves one file from {hlsRoot}/{libraryId}/{mediaId}/{profileKey}/hls/, parental-gated for `uid`.
// #78: profileKey == "default" when [profile] is null (today's layout plus one "/default" segment).
private suspend fun serveHlsFile(
    call: io.ktor.server.application.ApplicationCall,
    db: R2dbcDatabase,
    hlsRoot: Path,
    uid: UUID,
    coordinator: wtf.jobin.scanner.TranscodeCoordinator,
    profile: wtf.jobin.stremio.CapabilityProfile?,
) {
    val file = call.parameters["file"]!!
    // Path traversal guard: any separator or parent ref → 404 (don't leak).
    if (file.contains('/') || file.contains('\\') || file.contains("..")) {
        throw NotFoundException()
    }
    val mediaId = try {
        UUID.fromString(call.parameters["media_id"]!!)
    } catch (_: IllegalArgumentException) {
        throw NotFoundException()
    }
    // ponytail: one-shot column read; library_id + content_rating in one pass.
    val row = suspendTransaction(db) {
        MediaItems.selectAll()
            .where { MediaItems.id eq mediaId }
            .map { it[MediaItems.libraryId].value to it[MediaItems.contentRating] }
            .firstOrNull()
    } ?: throw NotFoundException()
    val (libraryId, contentRating) = row

    // Parental gate: blocked media 404s — never leak its existence.
    val max = wtf.jobin.rating.maxRatingFor(db, uid)
    if (!wtf.jobin.rating.isVisible(max, contentRating)) throw NotFoundException()

    // #78: profile-aware cache dir. profileKeyOf(null) == "default" → today's layout under /default.
    val profileKey = wtf.jobin.stremio.profileKeyOf(profile)
    val target = hlsRoot
        .resolve(libraryId.toString())
        .resolve(mediaId.toString())
        .resolve(profileKey)
        .resolve("hls")
        .resolve(file)
    // Phase 15 (#75): lazy transcode. The master playlist is the first thing an HLS player
    // fetches; if it isn't built yet, transcode now (deduped) and then serve. Segment/variant
    // requests arrive only after the playlist exists, so they never trigger a transcode.
    if (file == "playlist.m3u8" && !Files.isRegularFile(target)) {
        coordinator.ensure(mediaId, target, profile) // #78: build the profile-targeted rendition
    }
    if (!Files.isRegularFile(target)) throw NotFoundException()
    // Phase 15 (#80): touch the playlist on serve as the LRU access signal for cache eviction.
    if (file == "playlist.m3u8") {
        runCatching { Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(java.time.Instant.now())) }
        // #94 (Phase 18): playback start = master playlist served. Warm the NEXT episode at the
        // SAME profile in the background (depth +1, fire-and-forget). Wrapped in runCatching so it
        // can never block or fail the current response; movies / last-episode no-op inside.
        // ponytail: single next-item warm, no queue — multi-ahead + music prefetch are follow-ups.
        call.application.launch {
            runCatching { prefetchNextEpisode(db, hlsRoot, mediaId, profile, coordinator) }
                .onFailure { wtf.jobin.streaming.prefetchLog.warn("#94 prefetch failed for {}: {}", mediaId, it.toString()) }
        }
    }

    val ct = when (file.substringAfterLast('.', "").lowercase()) {
        "m3u8" -> M3U8_CT
        "ts" -> TS_CT
        "vtt" -> ContentType.parse("text/vtt")
        "jpg", "jpeg" -> ContentType.Image.JPEG
        else -> ContentType.Application.OctetStream
    }
    call.respond(LocalFileContent(target.toFile(), contentType = ct))
}
