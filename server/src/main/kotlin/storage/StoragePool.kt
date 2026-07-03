package wtf.jobin.storage

import java.util.UUID

/**
 * #127 (P2P-ADR 0011): storage-pool domain model + pure durability policy.
 *
 * The pool is user-scoped: one pool per identity (owner), spanning that user's
 * devices (nodes). Two content classes with different durability contracts:
 *
 *  - PRIVATE originals: replication factor >= 2 across pooled devices whenever
 *    >= 2 devices exist. Never RF=1. Never evicted. (ADR Decision 4)
 *  - PUBLIC cache:      RF=1, LRU-evictable, re-fetchable from the mesh. (Decision 4)
 *
 * Single-device users cannot satisfy RF>=2, so PRIVATE content on a 1-device pool
 * surfaces a loud data-loss warning rather than silently accepting RF=1. (Decision 5)
 *
 * This file is pure (no DB) so the invariant is unit-testable in isolation;
 * StoragePoolRepository wraps it over Exposed/R2DBC.
 */

enum class ContentClass {
    /** Owner's own originals — durable, RF>=2, never evicted. */
    PRIVATE,

    /** Publicly-seeded cache — RF=1, LRU-evictable, re-fetchable from mesh. */
    PUBLIC;

    companion object {
        fun fromDb(value: String): ContentClass = valueOf(value)
    }
}

/** A device's membership in a user's pool + its declared free-space contribution. */
data class PoolMember(
    val id: UUID,
    val ownerId: UUID,
    val nodeId: UUID,
    val freeSpaceBytes: Long,
    val contributedBytes: Long,
    val contributionPct: Int,
)

/**
 * Durability verdict for one content within a pool. [healthy] means the class's
 * replication contract is met; [singleDeviceWarning] is the loud Decision-5 flag.
 */
data class DurabilityStatus(
    val contentKey: String,
    val contentClass: ContentClass,
    val replicationFactor: Int,
    val poolMemberCount: Int,
    val healthy: Boolean,
    val singleDeviceWarning: Boolean,
    val message: String?,
)

/** Raised when an operation would violate the RF>=2 private-original invariant. */
class ReplicationInvariantException(message: String) : IllegalStateException(message)

/** Raised when a device pledges below the >=20% floor (Decision 1). */
class InsufficientContributionException(message: String) : IllegalArgumentException(message)

object StoragePoolPolicy {
    /** ADR Decision 1: each device dedicates a minimum of 20% of its free space. */
    const val MIN_CONTRIBUTION_PCT: Int = 20

    /** ADR Decision 4: private originals replicate across at least 2 devices. */
    const val PRIVATE_MIN_RF: Int = 2

    /** Bytes a device must dedicate given its free space and pledged percentage. */
    fun requiredContribution(freeSpaceBytes: Long, contributionPct: Int): Long =
        freeSpaceBytes * contributionPct / 100

    /** Validate a device's pledge against the >=20% floor. Throws otherwise. */
    fun validateContribution(freeSpaceBytes: Long, contributedBytes: Long, contributionPct: Int) {
        if (contributionPct < MIN_CONTRIBUTION_PCT) {
            throw InsufficientContributionException(
                "device pledged $contributionPct% but pool floor is $MIN_CONTRIBUTION_PCT% (P2P-ADR 0011 Decision 1)",
            )
        }
        val floor = requiredContribution(freeSpaceBytes, MIN_CONTRIBUTION_PCT)
        if (contributedBytes < floor) {
            throw InsufficientContributionException(
                "device dedicates $contributedBytes bytes but must dedicate >= $floor (20% of $freeSpaceBytes)",
            )
        }
    }

    /**
     * Is a PRIVATE original's current placement durable, given the pool size?
     * RF>=2 required when the pool has >=2 devices. A 1-device pool is *not*
     * durable (loud warning) but is not an invariant violation — the user simply
     * has nowhere else to replicate to (that is the single-device escape-hatch
     * case for the backup tier, a later slice).
     */
    fun privateIsDurable(replicationFactor: Int, poolMemberCount: Int): Boolean =
        if (poolMemberCount >= PRIVATE_MIN_RF) replicationFactor >= PRIVATE_MIN_RF else false

    /**
     * A multi-device pool holding a PRIVATE original at RF<2 is a hard invariant
     * violation ("never RF=1"): the placement engine failed its job and must be
     * rejected/repaired rather than persisted.
     */
    fun violatesPrivateInvariant(replicationFactor: Int, poolMemberCount: Int): Boolean =
        contentIsPrivateAtRisk(replicationFactor, poolMemberCount)

    private fun contentIsPrivateAtRisk(replicationFactor: Int, poolMemberCount: Int): Boolean =
        poolMemberCount >= PRIVATE_MIN_RF && replicationFactor < PRIVATE_MIN_RF

    /** Compute the durability verdict for a content given its RF and pool size. */
    fun evaluate(
        contentKey: String,
        contentClass: ContentClass,
        replicationFactor: Int,
        poolMemberCount: Int,
    ): DurabilityStatus = when (contentClass) {
        ContentClass.PUBLIC -> DurabilityStatus(
            contentKey = contentKey,
            contentClass = contentClass,
            replicationFactor = replicationFactor,
            poolMemberCount = poolMemberCount,
            // Public cache is healthy as long as it exists somewhere (RF>=1) or is
            // re-fetchable from the mesh; RF=0 just means "not cached right now".
            healthy = true,
            singleDeviceWarning = false,
            message = null,
        )

        ContentClass.PRIVATE -> {
            val durable = privateIsDurable(replicationFactor, poolMemberCount)
            val singleDevice = poolMemberCount < PRIVATE_MIN_RF
            DurabilityStatus(
                contentKey = contentKey,
                contentClass = contentClass,
                replicationFactor = replicationFactor,
                poolMemberCount = poolMemberCount,
                healthy = durable,
                singleDeviceWarning = singleDevice && replicationFactor >= 1,
                message = when {
                    singleDevice && replicationFactor >= 1 ->
                        "WARNING: private original '$contentKey' is on ONE device only — " +
                            "add another device or enable the encrypted backup tier to avoid data loss."
                    replicationFactor == 0 ->
                        "private original '$contentKey' has no replica in the pool."
                    !durable ->
                        "private original '$contentKey' is at RF=$replicationFactor across " +
                            "$poolMemberCount devices — below the required RF>=$PRIVATE_MIN_RF."
                    else -> null
                },
            )
        }
    }
}
