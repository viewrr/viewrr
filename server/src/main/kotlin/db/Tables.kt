package wtf.jobin.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

// ponytail: only tables with active Kotlin callers live here.
// SQL schema in V1__init.sql owns the full 8-table contract; add the Exposed
// mirror for {WatchEvents, Downloads, PartyRooms, PartyMembers, UserRecommendations}
// when the feature that reads them lands.

object Users : UUIDTable("users") {
    val username = varchar("username", 64).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = text("password_hash")
    val displayName = varchar("display_name", 255).nullable()
    val isAdmin = bool("is_admin").default(false)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

object Libraries : UUIDTable("libraries") {
    val name = varchar("name", 255)
    val kind = varchar("kind", 32)
    val rootPath = text("root_path").uniqueIndex()
    val createdAt = timestamp("created_at")
}

object MediaItems : UUIDTable("media_items") {
    val libraryId = reference("library_id", Libraries.id, onDelete = ReferenceOption.CASCADE)
    val title = text("title")
    val originalPath = text("original_path").uniqueIndex()
    val hlsPath = text("hls_path").nullable()
    val durationSecs = integer("duration_secs").nullable()
    val sizeBytes = long("size_bytes").nullable()
    val mimeType = varchar("mime_type", 127).nullable()
    val year = short("year").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
