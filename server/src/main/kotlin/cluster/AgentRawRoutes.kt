package wtf.jobin.cluster

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 15 (#76): the Agent's raw byte endpoint. The Hub's ffmpeg reads source media from
 * here (stream-source transcode, #74). Mounted only in AGENT mode.
 *
 * `GET /raw?path=<abs path>` with header `X-Viewrr-Token: <enrollment secret>`. The path must
 * resolve (after normalization) under a configured library root — the traversal guard. Range
 * requests are supported via PartialContent (needed for MP4 moov seek).
 *
 * ponytail: auth is the shared enrollment secret for v0 (both Hub and Agent hold it; plaintext
 * LAN per ADR). The per-node token (#73) is the upgrade once the Hub stores a presentable token,
 * not just its hash.
 */
fun Route.agentRawRoutes(enrollmentSecret: String, libraryRoots: List<String>) {
    val roots = libraryRoots.map { Path.of(it).toAbsolutePath().normalize() }
    route("/raw") {
        install(PartialContent)
        get {
            // token via header or query (ffmpeg can't set headers easily; #74 passes it in the URL)
            val token = call.request.queryParameters["token"] ?: call.request.headers["X-Viewrr-Token"]
            if (token != enrollmentSecret) {
                call.respond(HttpStatusCode.Unauthorized); return@get
            }
            val pathParam = call.request.queryParameters["path"]
            if (pathParam.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest); return@get }
            val target = Path.of(pathParam).toAbsolutePath().normalize()
            // 404 (don't leak) when outside every configured root or not a real file.
            if (roots.none { target.startsWith(it) } || !Files.isRegularFile(target)) {
                call.respond(HttpStatusCode.NotFound); return@get
            }
            call.respond(LocalFileContent(target.toFile(), contentType = ContentType.Application.OctetStream))
        }
    }
}
