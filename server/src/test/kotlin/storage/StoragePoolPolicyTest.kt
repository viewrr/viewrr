package wtf.jobin.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #127 (P2P-ADR 0011): pure durability-policy invariants — no DB. */
class StoragePoolPolicyTest {

    // --- Decision 1: >=20% free-space floor ---

    @Test
    fun rejectsPledgeBelowTwentyPercent() {
        assertFailsWith<InsufficientContributionException> {
            StoragePoolPolicy.validateContribution(
                freeSpaceBytes = 1_000,
                contributedBytes = 200,
                contributionPct = 19,
            )
        }
    }

    @Test
    fun rejectsBytesBelowTwentyPercentFloorEvenIfPctClaimsOk() {
        // Claims 20% but dedicates fewer bytes than 20% of free space.
        assertFailsWith<InsufficientContributionException> {
            StoragePoolPolicy.validateContribution(
                freeSpaceBytes = 1_000,
                contributedBytes = 150, // floor is 200
                contributionPct = 20,
            )
        }
    }

    @Test
    fun acceptsExactlyTwentyPercent() {
        StoragePoolPolicy.validateContribution(1_000, 200, 20) // no throw
        assertEquals(200, StoragePoolPolicy.requiredContribution(1_000, 20))
    }

    @Test
    fun acceptsAboveFloor() {
        StoragePoolPolicy.validateContribution(1_000, 500, 50) // no throw
    }

    // --- Decision 4: private originals RF>=2 across pooled devices ---

    @Test
    fun privateRfTwoOnTwoDevicesIsDurable() {
        assertTrue(StoragePoolPolicy.privateIsDurable(replicationFactor = 2, poolMemberCount = 2))
        assertFalse(StoragePoolPolicy.violatesPrivateInvariant(2, 2))
    }

    @Test
    fun privateRfOneOnTwoDevicesViolatesInvariant() {
        // "never RF=1" — two devices exist, so RF=1 is a hard violation.
        assertFalse(StoragePoolPolicy.privateIsDurable(replicationFactor = 1, poolMemberCount = 2))
        assertTrue(StoragePoolPolicy.violatesPrivateInvariant(1, 2))
    }

    @Test
    fun privateRfOneOnSingleDeviceIsNotInvariantButNotDurable() {
        // One device: RF=1 is unavoidable, so it is NOT an invariant violation,
        // but it is not durable — it must raise the loud warning instead.
        assertFalse(StoragePoolPolicy.violatesPrivateInvariant(1, 1))
        assertFalse(StoragePoolPolicy.privateIsDurable(1, 1))
    }

    // --- Decision 5: loud single-device warning ---

    @Test
    fun singleDevicePrivateSurfacesLoudWarning() {
        val status = StoragePoolPolicy.evaluate(
            contentKey = "vault/tax-2025.pdf",
            contentClass = ContentClass.PRIVATE,
            replicationFactor = 1,
            poolMemberCount = 1,
        )
        assertTrue(status.singleDeviceWarning)
        assertFalse(status.healthy)
        assertTrue(status.message!!.contains("ONE device"))
    }

    @Test
    fun multiDevicePrivateAtRfTwoIsHealthyNoWarning() {
        val status = StoragePoolPolicy.evaluate("vault/tax-2025.pdf", ContentClass.PRIVATE, 2, 3)
        assertTrue(status.healthy)
        assertFalse(status.singleDeviceWarning)
        assertEquals(null, status.message)
    }

    @Test
    fun publicCacheAtRfOneIsHealthyAndNeverWarns() {
        val status = StoragePoolPolicy.evaluate("public/movie-42", ContentClass.PUBLIC, 1, 1)
        assertTrue(status.healthy)
        assertFalse(status.singleDeviceWarning)
    }
}
