package wtf.jobin.collection

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreateCollectionRequest(val name: String)

@Serializable
data class AddItemRequest(val mediaId: String, val position: Int = 0)

@Serializable
data class CollectionView(val id: String, val name: String, val createdAt: String)

@Serializable
data class CollectionItemView(val mediaId: String, val title: String, val hlsPath: String?, val position: Int)

@Serializable
data class CollectionDetailView(
    val id: String,
    val name: String,
    val createdAt: String,
    val items: List<CollectionItemView>,
)

fun Route.collectionRoutes(repo: CollectionRepository) {
    authenticate("auth-jwt") {
        route("/collections") {

            post {
                val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
                val body = call.receive<CreateCollectionRequest>()
                if (body.name.isBlank()) throw IllegalArgumentException("name is required")
                val row = repo.create(uid, body.name)
                call.respond(HttpStatusCode.Created, row.toView())
            }

            get {
                val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
                call.respond(repo.listByOwner(uid).map { it.toView() })
            }

            get("/{id}") {
                val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
                val id = call.pathUuid("id")
                val row = repo.get(uid, id) ?: throw NotFoundException()
                val items = repo.items(uid, id) ?: throw NotFoundException()
                call.respond(
                    CollectionDetailView(
                        id = row.id.toString(),
                        name = row.name,
                        createdAt = row.createdAt.toString(),
                        items = items.map { it.toView() },
                    ),
                )
            }

            delete("/{id}") {
                val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
                val id = call.pathUuid("id")
                if (!repo.delete(uid, id)) throw NotFoundException()
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{id}/items") {
                val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
                val id = call.pathUuid("id")
                val body = call.receive<AddItemRequest>()
                val mediaId = try {
                    UUID.fromString(body.mediaId)
                } catch (_: IllegalArgumentException) {
                    throw NotFoundException()
                }
                if (!repo.addItem(uid, id, mediaId, body.position)) throw NotFoundException()
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{id}/items/{mediaId}") {
                val uid = UUID.fromString(call.principal<JWTPrincipal>()!!.subject!!)
                val id = call.pathUuid("id")
                val mediaId = call.pathUuid("mediaId")
                if (!repo.removeItem(uid, id, mediaId)) throw NotFoundException()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

// Bad UUID in the path is a 404, not a 400 — the route simply doesn't exist for that value.
private fun ApplicationCall.pathUuid(name: String): UUID = try {
    UUID.fromString(parameters[name]!!)
} catch (_: IllegalArgumentException) {
    throw NotFoundException()
}

private fun CollectionRow.toView() = CollectionView(
    id = id.toString(),
    name = name,
    createdAt = createdAt.toString(),
)

private fun CollectionItemRow.toView() = CollectionItemView(
    mediaId = mediaId.toString(),
    title = title,
    hlsPath = hlsPath,
    position = position,
)
