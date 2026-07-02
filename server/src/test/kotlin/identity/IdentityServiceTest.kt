package wtf.jobin.identity

import wtf.jobin.auth.FakeTokenService
import wtf.jobin.auth.dormantDb
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-memory account store; counts create() calls to prove idempotency. */
class FakeIdentityAccountRepository : IdentityAccountRepository(dormantDb) {
    private val byKey = mutableMapOf<String, IdentityAccountRow>()
    var createCount = 0
        private set

    override suspend fun findByPublicKey(publicKeyHex: String): IdentityAccountRow? = byKey[publicKeyHex]

    override suspend fun create(publicKeyHex: String, displayName: String?): IdentityAccountRow {
        createCount++
        val row = IdentityAccountRow(UUID.randomUUID(), publicKeyHex, displayName)
        byKey[publicKeyHex] = row
        return row
    }
}

/** In-memory single-use nonces mirroring RedisChallengeStore semantics. */
class FakeChallengeStore : ChallengeStore {
    private val live = mutableSetOf<String>()
    private var counter = 0

    override suspend fun issue(): String = "challenge-${counter++}".also { live += it }
    override suspend fun consume(challenge: String): Boolean = live.remove(challenge)
}

// Typed suspend () -> Unit so the last expression in each test (often assertFailsWith, which
// returns the caught Throwable) is coerced to Unit — JUnit4 requires void test methods.
private fun runIdentityTest(block: suspend () -> Unit) = kotlinx.coroutines.runBlocking { block() }

class IdentityServiceTest {

    private fun service(
        accounts: FakeIdentityAccountRepository = FakeIdentityAccountRepository(),
        challenges: FakeChallengeStore = FakeChallengeStore(),
        adminKeys: Set<String> = emptySet(),
    ) = IdentityService(accounts, challenges, FakeTokenService(), adminKeys)

    // ---- register ----

    @Test
    fun registerNewKeyCreatesOnceAndReturnsCreated() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val svc = service(accounts)
        val id = Ed25519TestIdentity()

        val (view, created) = svc.register(
            RegisterIdentityRequest(id.publicKeyHex, id.sign(IdentityService.REGISTER_MESSAGE)),
        )

        assertTrue(created)
        assertEquals(1, accounts.createCount)
        assertEquals(id.publicKeyHex, view.publicKey)
    }

    @Test
    fun registerStoresDisplayNameAndIsSetOnce() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val svc = service(accounts)
        val id = Ed25519TestIdentity()
        val sig = id.sign(IdentityService.REGISTER_MESSAGE)

        val (first, _) = svc.register(RegisterIdentityRequest(id.publicKeyHex, sig, "jobin"))
        assertEquals("jobin", first.displayName) // petname round-trips

        // Re-register with a different name must NOT overwrite (set-once semantics).
        val (second, created) = svc.register(RegisterIdentityRequest(id.publicKeyHex, sig, "someone-else"))
        assertFalse(created)
        assertEquals("jobin", second.displayName)
    }

    @Test
    fun registerBadSignatureRejectedAndNothingCreated() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val svc = service(accounts)
        val id = Ed25519TestIdentity()

        assertFailsWith<IdentityError.BadSignature> {
            // Signs the wrong message, so the register signature does not verify.
            svc.register(RegisterIdentityRequest(id.publicKeyHex, id.sign("not-register".toByteArray())))
        }
        assertEquals(0, accounts.createCount)
    }

    @Test
    fun registerExistingKeyIsIdempotent() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val svc = service(accounts)
        val id = Ed25519TestIdentity()
        val req = RegisterIdentityRequest(id.publicKeyHex, id.sign(IdentityService.REGISTER_MESSAGE))

        val (_, firstCreated) = svc.register(req)
        val (_, secondCreated) = svc.register(req)

        assertTrue(firstCreated)
        assertFalse(secondCreated)
        assertEquals(1, accounts.createCount) // no dup insert
    }

    // ---- challenge -> verify ----

    @Test
    fun challengeThenVerifyIssuesTokens() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val challenges = FakeChallengeStore()
        val svc = service(accounts, challenges)
        val id = Ed25519TestIdentity()
        svc.register(RegisterIdentityRequest(id.publicKeyHex, id.sign(IdentityService.REGISTER_MESSAGE)))

        val challenge = svc.issueChallenge().challenge
        val pair = svc.verify(VerifyIdentityRequest(id.publicKeyHex, challenge, id.sign(challenge)))

        assertTrue(pair.accessToken.isNotEmpty())
        assertTrue(pair.refreshToken.isNotEmpty())
    }

    @Test
    fun verifyUnknownChallengeRejected() = runIdentityTest {
        val svc = service()
        val id = Ed25519TestIdentity()

        assertFailsWith<IdentityError.InvalidChallenge> {
            svc.verify(VerifyIdentityRequest(id.publicKeyHex, "never-issued", id.sign("never-issued")))
        }
    }

    @Test
    fun verifyConsumedChallengeCannotReplay() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val challenges = FakeChallengeStore()
        val svc = service(accounts, challenges)
        val id = Ed25519TestIdentity()
        svc.register(RegisterIdentityRequest(id.publicKeyHex, id.sign(IdentityService.REGISTER_MESSAGE)))

        val challenge = svc.issueChallenge().challenge
        val req = VerifyIdentityRequest(id.publicKeyHex, challenge, id.sign(challenge))
        svc.verify(req) // first spend succeeds

        assertFailsWith<IdentityError.InvalidChallenge> { svc.verify(req) } // replay burned
    }

    @Test
    fun verifyBadSignatureRejected() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val challenges = FakeChallengeStore()
        val svc = service(accounts, challenges)
        val id = Ed25519TestIdentity()
        svc.register(RegisterIdentityRequest(id.publicKeyHex, id.sign(IdentityService.REGISTER_MESSAGE)))

        val challenge = svc.issueChallenge().challenge
        assertFailsWith<IdentityError.BadSignature> {
            // Valid live challenge, but the signature is over different bytes.
            svc.verify(VerifyIdentityRequest(id.publicKeyHex, challenge, id.sign("wrong-bytes")))
        }
    }

    @Test
    fun verifyUnregisteredKeyRejected() = runIdentityTest {
        val challenges = FakeChallengeStore()
        val svc = service(challenges = challenges)
        val id = Ed25519TestIdentity() // never registered

        val challenge = svc.issueChallenge().challenge
        assertFailsWith<IdentityError.UnknownAccount> {
            svc.verify(VerifyIdentityRequest(id.publicKeyHex, challenge, id.sign(challenge)))
        }
    }

    // ---- admin allowlist (#120: admin claim source after Keycloak retirement) ----
    // FakeTokenService.issueAccess encodes isAdmin as the trailing "access:<uuid>:<isAdmin>",
    // so the token suffix proves which admin flag the verify path minted.

    @Test
    fun verifyGrantsAdminWhenKeyOnAllowlist() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val challenges = FakeChallengeStore()
        val id = Ed25519TestIdentity()
        val svc = service(accounts, challenges, adminKeys = setOf(id.publicKeyHex))
        svc.register(RegisterIdentityRequest(id.publicKeyHex, id.sign(IdentityService.REGISTER_MESSAGE)))

        val challenge = svc.issueChallenge().challenge
        val pair = svc.verify(VerifyIdentityRequest(id.publicKeyHex, challenge, id.sign(challenge)))

        assertTrue(pair.accessToken.endsWith(":true"), "admin key must mint an admin access token")
    }

    @Test
    fun verifyIsNonAdminWhenKeyNotOnAllowlist() = runIdentityTest {
        val accounts = FakeIdentityAccountRepository()
        val challenges = FakeChallengeStore()
        val id = Ed25519TestIdentity()
        val other = Ed25519TestIdentity()
        // Allowlist holds a DIFFERENT key — this identity must not be admin.
        val svc = service(accounts, challenges, adminKeys = setOf(other.publicKeyHex))
        svc.register(RegisterIdentityRequest(id.publicKeyHex, id.sign(IdentityService.REGISTER_MESSAGE)))

        val challenge = svc.issueChallenge().challenge
        val pair = svc.verify(VerifyIdentityRequest(id.publicKeyHex, challenge, id.sign(challenge)))

        assertTrue(pair.accessToken.endsWith(":false"), "non-allowlisted key must be non-admin")
    }
}
