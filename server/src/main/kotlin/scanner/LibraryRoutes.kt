package wtf.jobin.scanner

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import wtf.jobin.music.MusicScanner
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Serializable
data class CreateLibraryRequest(
    val name: String,
    val kind: String,
    val rootPath: String,
)

@Serializable
data class PatchLibraryRequest(
    val name: String? = null,
    val watchEnabled: Boolean? = null,
)

fun Route.libraryRoutes(repo: LibraryRepository, watcher: LibraryWatcher, scanner: MediaScanner, musicScanner: MusicScanner) {
    authenticate("auth-jwt") {
        route("/admin/libraries") {

            post {
                call.requireAdmin()
                val body = call.receive<CreateLibraryRequest>()
                if (body.name.isBlank()) throw IllegalArgumentException("name is required")
                if (body.kind.isBlank()) throw IllegalArgumentException("kind is required")
                if (body.rootPath.isBlank()) throw IllegalArgumentException("rootPath is required")
                val path = Path.of(body.rootPath)
                if (!Files.isDirectory(path)) throw IllegalArgumentException("rootPath is not a directory: ${body.rootPath}")
                if (!Files.isReadable(path)) throw IllegalArgumentException("rootPath is not readable: ${body.rootPath}")
                val row = repo.create(body.name, body.kind, path.toAbsolutePath().toString())
                // Personal folders mix video + music — index both regardless of kind.
                // kind governs live-watch only (video FS events; music caught on rescan).
                if (row.kind != "music" && row.watchEnabled) watcher.watch(row.id, Path.of(row.rootPath))
                call.application.launch {
                    runCatching { scanner.scan(row.id) }
                    runCatching { musicScanner.scan(row.id) }
                }
                call.respond(HttpStatusCode.Created, row.toView())
            }

            get {
                call.requireAdmin()
                call.respond(repo.list().map { it.toView() })
            }

            patch("/{id}") {
                call.requireAdmin()
                val id = UUID.fromString(call.parameters["id"]!!)
                val body = call.receive<PatchLibraryRequest>()
                if (body.name != null && body.name.isBlank()) throw IllegalArgumentException("name cannot be blank")
                val updated = repo.patch(id, body.name, body.watchEnabled) ?: throw NotFoundException()
                when (body.watchEnabled) {
                    true -> {
                        if (updated.kind == "music") {
                            // ponytail: no live-watch for music v1; rescan only.
                            call.application.launch { runCatching { musicScanner.scan(updated.id) } }
                        } else {
                            watcher.watch(updated.id, Path.of(updated.rootPath))
                            // Catch up on anything added while watch was off.
                            call.application.launch { runCatching { scanner.scan(updated.id) } }
                        }
                    }
                    false -> watcher.unwatch(updated.id)
                    null -> {}
                }
                call.respond(updated.toView())
            }

            delete("/{id}") {
                call.requireAdmin()
                val id = UUID.fromString(call.parameters["id"]!!)
                watcher.unwatch(id)
                if (!repo.delete(id)) throw NotFoundException()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

private fun ApplicationCall.requireAdmin() {
    val isAdmin = principal<JWTPrincipal>()
        ?.payload
        ?.getClaim("admin")
        ?.asBoolean() == true
    // 404 over 403 — don't leak admin surface.
    if (!isAdmin) throw NotFoundException()
}
