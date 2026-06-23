package wtf.jobin.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.util.UUID

// Phase 14 (#72): the implicit node owning all pre-split content (see V10 migration).
// Hub-local scan/library-create attach here until agent-side scan (#76) sets the real node.
val LOCAL_NODE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

// ponytail: only tables with active Kotlin callers live here.
// SQL schema in V1__init.sql owns the full 8-table contract; add the Exposed
// mirror when the feature that reads them lands.

object Users : UUIDTable("users") {
    val username = varchar("username", 64).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = text("password_hash")
    val displayName = varchar("display_name", 255).nullable()
    val isAdmin = bool("is_admin").default(false)
    val isActive = bool("is_active").default(true)
    val maxRating = varchar("max_rating", 16).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}

// Phase 14 (#72): a Node owns raw bytes; Hub holds this row. mesh/client addresses
// + last_seen filled at register/heartbeat (#69, #71, #83).
object Nodes : UUIDTable("nodes") {
    val name = text("name")
    val meshAddress = text("mesh_address").nullable()
    val clientAddress = text("client_address").nullable()
    val tokenHash = text("token_hash").nullable() // Phase 14 (#73): sha256 of per-node token
    val lastSeenAt = timestamp("last_seen_at").nullable()
    val createdAt = timestamp("created_at")
}

object Libraries : UUIDTable("libraries") {
    val nodeId = reference("node_id", Nodes.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val kind = varchar("kind", 32)
    val rootPath = text("root_path")
    val lastScannedAt = timestamp("last_scanned_at").nullable()
    val watchEnabled = bool("watch_enabled").default(true)
    val createdAt = timestamp("created_at")

    init { uniqueIndex(nodeId, rootPath) } // root_path unique per node, not globally
}

object MediaItems : UUIDTable("media_items") {
    val libraryId = reference("library_id", Libraries.id, onDelete = ReferenceOption.CASCADE)
    val nodeId = reference("node_id", Nodes.id, onDelete = ReferenceOption.CASCADE) // Phase 14 (#72)
    val title = text("title")
    val originalPath = text("original_path").uniqueIndex()
    val hlsPath = text("hls_path").nullable()
    val durationSecs = integer("duration_secs").nullable()
    val sizeBytes = long("size_bytes").nullable()
    val mimeType = varchar("mime_type", 127).nullable()
    val year = short("year").nullable()
    val cleanTitle = text("clean_title").nullable()
    val showTitle = text("show_title").nullable()
    val seasonNumber = integer("season_number").nullable()
    val episodeNumber = integer("episode_number").nullable()
    val contentRating = varchar("content_rating", 16).nullable()
    val tmdbId = integer("tmdb_id").nullable()
    val poster = text("poster").nullable()
    val backdrop = text("backdrop").nullable()
    val overview = text("overview").nullable()
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

object MusicTracks : UUIDTable("music_tracks") {
    val libraryId = reference("library_id", Libraries.id, onDelete = ReferenceOption.CASCADE)
    val title = text("title")
    val artist = text("artist").nullable()
    val album = text("album").nullable()
    val albumArtist = text("album_artist").nullable()
    val trackNumber = integer("track_number").nullable()
    val discNumber = integer("disc_number").nullable()
    val durationSecs = integer("duration_secs").nullable()
    val originalPath = text("original_path").uniqueIndex()
    val mimeType = varchar("mime_type", 127).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
}
