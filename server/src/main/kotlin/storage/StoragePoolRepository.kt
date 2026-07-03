package wtf.jobin.storage

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import wtf.jobin.db.PoolContentReplicas
import wtf.jobin.db.StoragePoolMembers
import java.time.Instant
import java.util.UUID

/**
 * #127 (P2P-ADR 0011): durable-state repository for the user-scoped storage pool.
 *
 * Enforces the two hard rules from the ADR at the write boundary:
 *   - Decision 1: a device must dedicate >= 20% of its free space (StoragePoolPolicy).
 *   - Decision 4/5: PRIVATE originals are RF>=2 across pooled devices; a multi-device
 *     pool holding a private original at RF<2 is rejected as an invariant violation,
 *     and a single-device pool surfaces the loud data-loss warning.
 *
 * Deliberately NOT here (later slices of #127): device-side space enforcement,
 * public-cache LRU eviction, and the encrypted NAS backup tier. This slice is
 * additive and does not touch the Title/Copy + heartbeat availability path.
 */
open class StoragePoolRepository(private val db: R2dbcDatabase) {

    /**
     * Add or update a device's membership in [ownerId]'s pool. Validates the >=20%
     * floor before persisting (throws [InsufficientContributionException] otherwise).
     * Idempotent on (owner, node): a re-declaration updates the pledge in place.
     */
    open suspend fun joinPool(
        ownerId: UUID,
        nodeId: UUID,
        freeSpaceBytes: Long,
        contributedBytes: Long,
        contributionPct: Int = StoragePoolPolicy.MIN_CONTRIBUTION_PCT,
    ): PoolMember {
        StoragePoolPolicy.validateContribution(freeSpaceBytes, contributedBytes, contributionPct)
        val now = Instant.now()
        return suspendTransaction(db) {
            val existing = StoragePoolMembers
                .select(StoragePoolMembers.id)
                .where { (StoragePoolMembers.ownerId eq ownerId) and (StoragePoolMembers.nodeId eq nodeId) }
                .map { it[StoragePoolMembers.id].value }
                .firstOrNull()

            val id = if (existing != null) {
                StoragePoolMembers.update({ StoragePoolMembers.id eq existing }) {
                    it[StoragePoolMembers.freeSpaceBytes] = freeSpaceBytes
                    it[StoragePoolMembers.contributedBytes] = contributedBytes
                    it[StoragePoolMembers.contributionPct] = contributionPct.toShort()
                    it[StoragePoolMembers.updatedAt] = now
                }
                existing
            } else {
                StoragePoolMembers.insertAndGetId {
                    it[StoragePoolMembers.ownerId] = ownerId
                    it[StoragePoolMembers.nodeId] = nodeId
                    it[StoragePoolMembers.freeSpaceBytes] = freeSpaceBytes
                    it[StoragePoolMembers.contributedBytes] = contributedBytes
                    it[StoragePoolMembers.contributionPct] = contributionPct.toShort()
                    it[StoragePoolMembers.joinedAt] = now
                    it[StoragePoolMembers.updatedAt] = now
                }.value
            }
            PoolMember(id, ownerId, nodeId, freeSpaceBytes, contributedBytes, contributionPct)
        }
    }

    open suspend fun poolMembers(ownerId: UUID): List<PoolMember> = suspendTransaction(db) {
        StoragePoolMembers.selectAll()
            .where { StoragePoolMembers.ownerId eq ownerId }
            .map { it.toMember() }
            .toList()
    }

    /** Number of distinct devices in [ownerId]'s pool — drives the RF policy. */
    open suspend fun poolMemberCount(ownerId: UUID): Int = suspendTransaction(db) {
        StoragePoolMembers.select(StoragePoolMembers.nodeId)
            .where { StoragePoolMembers.ownerId eq ownerId }
            .map { it[StoragePoolMembers.nodeId].value }
            .toList()
            .distinct()
            .size
    }

    /**
     * Record that [contentKey] has a replica on [nodeId]. Idempotent on
     * (owner, content, node): a repeat placement is a no-op. Returns true when a
     * new replica row was written.
     */
    open suspend fun placeReplica(
        ownerId: UUID,
        contentKey: String,
        contentClass: ContentClass,
        nodeId: UUID,
        sizeBytes: Long? = null,
    ): Boolean = suspendTransaction(db) {
        val already = PoolContentReplicas
            .select(PoolContentReplicas.id)
            .where {
                (PoolContentReplicas.ownerId eq ownerId) and
                    (PoolContentReplicas.contentKey eq contentKey) and
                    (PoolContentReplicas.nodeId eq nodeId)
            }
            .map { it[PoolContentReplicas.id].value }
            .firstOrNull()
        if (already != null) return@suspendTransaction false

        PoolContentReplicas.insert {
            it[PoolContentReplicas.ownerId] = ownerId
            it[PoolContentReplicas.contentKey] = contentKey
            it[PoolContentReplicas.contentClass] = contentClass.name
            it[PoolContentReplicas.nodeId] = nodeId
            it[PoolContentReplicas.sizeBytes] = sizeBytes
            it[PoolContentReplicas.createdAt] = Instant.now()
        }
        true
    }

    /** Drop a single replica (e.g. device left / file removed). Returns rows deleted. */
    open suspend fun removeReplica(ownerId: UUID, contentKey: String, nodeId: UUID): Int =
        suspendTransaction(db) {
            PoolContentReplicas.deleteWhere {
                (PoolContentReplicas.ownerId eq ownerId) and
                    (PoolContentReplicas.contentKey eq contentKey) and
                    (PoolContentReplicas.nodeId eq nodeId)
            }
        }

    /** Replication factor = number of distinct devices holding [contentKey]. */
    open suspend fun replicationFactor(ownerId: UUID, contentKey: String): Int =
        suspendTransaction(db) {
            PoolContentReplicas.select(PoolContentReplicas.nodeId)
                .where {
                    (PoolContentReplicas.ownerId eq ownerId) and
                        (PoolContentReplicas.contentKey eq contentKey)
                }
                .map { it[PoolContentReplicas.nodeId].value }
                .toList()
                .distinct()
                .size
        }

    /** Durability verdict for one content (RF vs. class contract + single-device warning). */
    open suspend fun durabilityOf(
        ownerId: UUID,
        contentKey: String,
        contentClass: ContentClass,
    ): DurabilityStatus {
        val rf = replicationFactor(ownerId, contentKey)
        val members = poolMemberCount(ownerId)
        return StoragePoolPolicy.evaluate(contentKey, contentClass, rf, members)
    }

    /**
     * Hard-assert the "never RF=1 for a private original in a multi-device pool"
     * invariant (Decision 4). Throws [ReplicationInvariantException] when violated.
     * Call after any placement/removal that touches a PRIVATE original so a bad
     * state is surfaced immediately rather than silently persisted.
     */
    open suspend fun assertPrivateInvariant(ownerId: UUID, contentKey: String) {
        val rf = replicationFactor(ownerId, contentKey)
        val members = poolMemberCount(ownerId)
        if (StoragePoolPolicy.violatesPrivateInvariant(rf, members)) {
            throw ReplicationInvariantException(
                "private original '$contentKey' at RF=$rf across $members devices violates " +
                    "RF>=${StoragePoolPolicy.PRIVATE_MIN_RF} (P2P-ADR 0011 Decision 4)",
            )
        }
    }

    /**
     * Loud single-device warning (Decision 5): true when the pool has fewer than
     * two devices, so private originals cannot reach RF>=2 and are one wipe from loss.
     */
    open suspend fun singleDeviceWarning(ownerId: UUID): Boolean =
        poolMemberCount(ownerId) < StoragePoolPolicy.PRIVATE_MIN_RF

    private fun ResultRow.toMember() = PoolMember(
        id = this[StoragePoolMembers.id].value,
        ownerId = this[StoragePoolMembers.ownerId].value,
        nodeId = this[StoragePoolMembers.nodeId].value,
        freeSpaceBytes = this[StoragePoolMembers.freeSpaceBytes],
        contributedBytes = this[StoragePoolMembers.contributedBytes],
        contributionPct = this[StoragePoolMembers.contributionPct].toInt(),
    )
}
