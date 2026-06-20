package wtf.jobin.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

// ponytail: only tables with active Kotlin callers live here.
// SQL schema in V1__init.sql owns the full 8-table contract; add the Exposed
// mirror when the feature that reads them lands.

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

object WatchEvents : Table("watch_events") {
    val id = long("id").autoIncrement()
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val mediaId = reference("media_id", MediaItems.id, onDelete = ReferenceOption.CASCADE)
    val positionSecs = integer("position_secs")
    val eventType = varchar("event_type", 16)
    val sessionId = javaUUID("session_id")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object UserRecommendations : Table("user_recommendations") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val mediaId = reference("media_id", MediaItems.id, onDelete = ReferenceOption.CASCADE)
    val score = float("score")
    val rank = short("rank")
    val computedAt = timestamp("computed_at")
    override val primaryKey = PrimaryKey(userId, mediaId)
}

object PartyRooms : UUIDTable("party_rooms") {
    val ownerId = reference("owner_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val mediaId = reference("media_id", MediaItems.id, onDelete = ReferenceOption.CASCADE)
    val joinCode = varchar("join_code", 8).uniqueIndex()
    val positionSecs = integer("position_secs").default(0)
    val isPlaying = bool("is_playing").default(false)
    val lastSyncedAt = timestamp("last_synced_at")
    val closedAt = timestamp("closed_at").nullable()
    val createdAt = timestamp("created_at")
}

object PartyMembers : Table("party_members") {
    val roomId = reference("room_id", PartyRooms.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val joinedAt = timestamp("joined_at")
    val leftAt = timestamp("left_at").nullable()
    override val primaryKey = PrimaryKey(roomId, userId)
}

object Downloads : UUIDTable("downloads") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val mediaId = reference("media_id", MediaItems.id, onDelete = ReferenceOption.CASCADE)
    val deviceId = varchar("device_id", 128)
    val status = varchar("status", 16)
    val filePath = text("file_path").nullable()
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")

    init { uniqueIndex(userId, mediaId, deviceId) }
}
