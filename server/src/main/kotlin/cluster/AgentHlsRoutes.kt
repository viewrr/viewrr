package wtf.jobin.cluster

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * #95 (Phase 18): edge-cache HLS to a local Node. The Hub-side counterpart [HlsEdgePusher]
 * pushes a Hub-produced HLS bundle here so later same-LAN plays come over the LAN instead of
 * round-tripping through the Hub. This is the *receive/store* half (the agent's existing /raw is
 * read-only, so the upload path lives in its own gated route).
 *
 * Mounted ONLY in AGENT mode, and ONLY when edge-cache is enabled. When `media.edgeCacheEnabled`
 * is false (the default) these routes are never installed, so an Agent behaves byte-identically to
 * today (just /health + /raw).
 *
 * Auth is the shared enrollment secret (header `X-Viewrr-Token` or `?token=`), matching /raw's v0
 * LAN auth model (plaintext on LAN per ADR; per-node token is the #73 upgrade).
 *
 * Layout under [hlsCacheRoot]: `{lib}/{media}/{profileKey}/hls/<file>` — the SAME relative shape the
 * Hub uses ({hlsRoot}/{lib}/{media}/{profileKey}/hls), so a player's relative segment/variant URIs
 * resolve unchanged when pointed at the Node.
 *
 * Endpoints (all gated by the shared token):
 *   PUT  /hls/{lib}/{media}/{profileKey}/{file}  — store ONE bundle file (playlist/variant/segment).
 *   GET  /hls/{lib}/{media}/{profileKey}/hls/{file} — serve a stored file over the LAN (Range-capable).
 *
 * ponytail: per-file PUT (not a tar/zip) keeps the receiver trivial and dependency-free — the
 * pusher walks the dir and uploads each file. Fine for personal-scale bundles (a handful of MB to a
 * few GB across small .ts segments). Switch to a single archived upload if bundle counts explode.
 *
 * TODO needs node deployed: end-to-end verification (push -> store -> LAN serve) requires a real
 * second Node reachable over the mesh/LAN (Headscale deploy). Until then this is exercised only by
 * the compile path and the default-off guard.
 */
fun Route.agentHlsRoutes(enrollmentSecret: String, hlsCacheRoot: String) {
    val root = Path.of(hlsCacheRoot).toAbsolutePath().normalize()
    route("/hls") {
        install(PartialContent)

        // Receive one bundle file. The relative path is built from typed segments (no client-supplied
        // separators), and the resolved target is re-checked to live under [root] — defense in depth.
        put("/{lib}/{media}/{profileKey}/{file}") {
            val token = call.request.queryParameters["token"] ?: call.request.headers["X-Viewrr-Token"]
            if (token != enrollmentSecret) { call.respond(HttpStatusCode.Unauthorized); return@put }

            val target = resolveBundleFile(root, call.parameters) ?: run {
                call.respond(HttpStatusCode.BadRequest); return@put
            }
            // #95: stream the body straight to disk so large segments never buffer fully in memory.
            // receiveStream() gives a plain InputStream; copy on the IO dispatcher (blocking write).
            val body = call.receiveStream()
            withContext(Dispatchers.IO) {
                Files.createDirectories(target.parent)
                Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // Serve a stored bundle file over the LAN. Path shape mirrors the Hub /stream layout so a
        // playlist's relative refs resolve here unchanged.
        get("/{lib}/{media}/{profileKey}/hls/{file}") {
            val token = call.request.queryParameters["token"] ?: call.request.headers["X-Viewrr-Token"]
            if (token != enrollmentSecret) { call.respond(HttpStatusCode.Unauthorized); return@get }

            val target = resolveServeFile(root, call.parameters) ?: run {
                call.respond(HttpStatusCode.NotFound); return@get
            }
            if (!Files.isRegularFile(target)) { call.respond(HttpStatusCode.NotFound); return@get }
            call.respond(LocalFileContent(target.toFile(), contentType = hlsContentType(target)))
        }
    }
}

// Build {root}/{lib}/{media}/{profileKey}/hls/{file} from typed UUID/segment params; null on any
// malformed/unsafe segment. (PUT target — note the implicit "hls" dir so it matches the serve path.)
private fun resolveBundleFile(root: Path, params: io.ktor.http.Parameters): Path? {
    val lib = params["lib"]?.let { safeUuid(it) } ?: return null
    val media = params["media"]?.let { safeUuid(it) } ?: return null
    val profileKey = params["profileKey"]?.takeIf(::isSafeSegment) ?: return null
    val file = params["file"]?.takeIf(::isSafeSegment) ?: return null
    val target = root.resolve(lib).resolve(media).resolve(profileKey).resolve("hls").resolve(file)
        .toAbsolutePath().normalize()
    return target.takeIf { it.startsWith(root) }
}

private fun resolveServeFile(root: Path, params: io.ktor.http.Parameters): Path? = resolveBundleFile(root, params)

private fun safeUuid(s: String): String? = runCatching { UUID.fromString(s).toString() }.getOrNull()

// No path separators, no parent refs — the params are single path segments only.
private fun isSafeSegment(s: String): Boolean =
    s.isNotBlank() && !s.contains('/') && !s.contains('\\') && !s.contains("..")

private val M3U8_CT = ContentType.parse("application/vnd.apple.mpegurl")
private val TS_CT = ContentType.parse("video/mp2t")

private fun hlsContentType(p: Path): ContentType = when (p.fileName.toString().substringAfterLast('.', "").lowercase()) {
    "m3u8" -> M3U8_CT
    "ts" -> TS_CT
    "vtt" -> ContentType.parse("text/vtt")
    "jpg", "jpeg" -> ContentType.Image.JPEG
    else -> ContentType.Application.OctetStream
}
