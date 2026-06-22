package wtf.jobin.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Refresh-token paths need Redis (skipped here — noOpRedis is never invoked); scoped to the pure JWT path.
class TokenServiceTest {
    private val svc = TokenService(testAuthConfig, noOpRedis())

    private fun verifier(secret: String) = JWT
        .require(Algorithm.HMAC256(secret))
        .withIssuer(testAuthConfig.jwtIssuer)
        .withAudience(testAuthConfig.jwtAudience)
        .build()

    @Test
    fun issuedTokenVerifiesUnderSameSecretIssuerAudience() {
        val id = UUID.randomUUID()
        val decoded = verifier(testAuthConfig.jwtSecret).verify(svc.issueAccess(id, isAdmin = false))
        assertEquals(id.toString(), decoded.subject)
        assertEquals(testAuthConfig.jwtIssuer, decoded.issuer)
        assertTrue(testAuthConfig.jwtAudience in decoded.audience)
    }

    @Test
    fun adminClaimRoundTrips() {
        val admin = verifier(testAuthConfig.jwtSecret).verify(svc.issueAccess(UUID.randomUUID(), isAdmin = true))
        assertEquals(true, admin.getClaim("admin").asBoolean())
        val regular = verifier(testAuthConfig.jwtSecret).verify(svc.issueAccess(UUID.randomUUID(), isAdmin = false))
        assertEquals(false, regular.getClaim("admin").asBoolean())
    }

    @Test
    fun verificationFailsUnderWrongSecret() {
        val token = svc.issueAccess(UUID.randomUUID(), isAdmin = false)
        assertFailsWith<JWTVerificationException> { verifier("a-different-secret").verify(token) }
    }
}
