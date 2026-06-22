package wtf.jobin.auth

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthServiceTest {

    @Test
    fun registerNewUsernameReturnsPairAndCreatesOnce() = runBlocking {
        val users = FakeUserRepository()
        val svc = AuthService(users, FakePasswordHasher(), FakeTokenService())

        val pair = svc.register(RegisterRequest("alice", "alice@example.com", "pw", "Alice"))

        assertEquals(1, users.createCount)
        assertTrue(pair.accessToken.isNotEmpty())
        assertTrue(pair.refreshToken.isNotEmpty())
    }

    @Test
    fun registerTakenUsernameThrowsUsernameTaken() = runBlocking {
        val users = FakeUserRepository().apply {
            seed(UserRow(UUID.randomUUID(), "bob", "bob@example.com", "hash:pw", null, isAdmin = false, isActive = true, maxRating = null))
        }
        val svc = AuthService(users, FakePasswordHasher(), FakeTokenService())

        assertFailsWith<AuthError.UsernameTaken> {
            svc.register(RegisterRequest("bob", "other@example.com", "pw"))
        }
        assertEquals(0, users.createCount)
    }

    @Test
    fun loginWrongPasswordThrowsInvalidCredentials() = runBlocking {
        val users = FakeUserRepository().apply {
            seed(UserRow(UUID.randomUUID(), "carol", "carol@example.com", "hash:secret", null, isAdmin = false, isActive = true, maxRating = null))
        }
        val hasher = FakePasswordHasher(verifyResult = false)
        val svc = AuthService(users, hasher, FakeTokenService())

        assertFailsWith<AuthError.InvalidCredentials> { svc.login(LoginRequest("carol", "wrong")) }
        assertTrue(hasher.verifyCount >= 1)
    }

    @Test
    fun loginUnknownUserThrowsInvalidCredentialsAndStillVerifies() = runBlocking {
        val users = FakeUserRepository() // empty store
        val hasher = FakePasswordHasher(verifyResult = false)
        val svc = AuthService(users, hasher, FakeTokenService())

        assertFailsWith<AuthError.InvalidCredentials> { svc.login(LoginRequest("ghost", "pw")) }
        // Timing-oracle guard: verify() must run even when the user does not exist.
        assertEquals(1, hasher.verifyCount)
    }
}
