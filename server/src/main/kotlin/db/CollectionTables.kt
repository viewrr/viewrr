package wtf.jobin.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.java.UUIDTable
import org.jetbrains.exposed.v1.javatime.timestamp

// Mirrors V6__collections.sql. Flyway owns the DDL; these only describe it for Exposed.

object Collections : UUIDTable("collections") {
    // #120 B1: principal unified onto identity_accounts (was Users.id). owner_id carries the
    // auth subject id (JWT sub = identity_accounts.id), same as the other user-scoped FKs.
    val ownerId = reference("owner_id", IdentityAccounts.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 255)
    val createdAt = timestamp("created_at")
}

object CollectionItems : Table("collection_items") {
    val collectionId = reference("collection_id", Collections.id, onDelete = ReferenceOption.CASCADE)
    val mediaId = reference("media_id", MediaItems.id, onDelete = ReferenceOption.CASCADE)
    val position = integer("position").default(0)
    val addedAt = timestamp("added_at")
    override val primaryKey = PrimaryKey(collectionId, mediaId)
}
