package wtf.jobin.identity

import wtf.jobin.auth.TokenPair
import wtf.jobin.auth.TokenService

sealed class IdentityError(msg: String) : RuntimeException(msg) {
    class BadSignature : IdentityError("signature does not verify")
    class InvalidChallenge : IdentityError("challenge is unknown, expired, or already used")
    class UnknownAccount : IdentityError("public key is not registered")
}

/**
 * #120 self-custody identity. Proves control of an Ed25519 key pair, then issues the app's
 * HS256 JWT/refresh session (TokenService) keyed by the account's internal UUID — so every
 * downstream route works unchanged. As of increment 2 this is the SOLE auth path: Keycloak/OIDC
 * and the argon2 local login are retired.
 *
 * Admin: the `admin` JWT claim that gates admin routes is now sourced from [adminPublicKeys] — a
 * config allowlist of Ed25519 keys (lowercase hex, viewrr.auth.adminPublicKeys). This replaces the
 * retired Keycloak realm-role / users.is_admin source. There is no runtime promotion endpoint;
 * admin is "you hold a key on the allowlist". Empty allowlist ⇒ no admins.
 */
class IdentityService(
    private val accounts: IdentityAccountRepository,
    private val challenges: ChallengeStore,
    private val tokens: TokenService,
    private val adminPublicKeys: Set<String> = emptySet(),
) {
    /** Register (or idempotently re-hit) an account. Returns whether the row was freshly created. */
    suspend fun register(req: RegisterIdentityRequest): Pair<AccountView, Boolean> {
        val pk = Ed25519Verifier.normalizeKey(req.publicKey)
        if (!Ed25519Verifier.verify(pk, req.signature, REGISTER_MESSAGE)) throw IdentityError.BadSignature()

        // ponytail: displayName is set once, at first register. Re-registering an existing key
        // returns the stored row unchanged (no overwrite) — an authed rename is a later increment.
        accounts.findByPublicKey(pk)?.let { return it.toView() to false }
        val row = try {
            accounts.create(pk, req.displayName)
        } catch (e: Exception) {
            // Lost a create race: another request inserted the same key between our lookup and insert.
            if (isUniqueViolation(e)) {
                val existing = accounts.findByPublicKey(pk) ?: throw e
                return existing.toView() to false
            }
            throw e
        }
        return row.toView() to true
    }

    /** Mint a fresh single-use challenge nonce for the client to sign. */
    suspend fun issueChallenge(): ChallengeResponse = ChallengeResponse(challenges.issue())

    /**
     * Verify a signed challenge and, on success, issue the app's normal session tokens. The
     * challenge is consumed first (single-use) so a replayed body can't mint a second session.
     */
    suspend fun verify(req: VerifyIdentityRequest): TokenPair {
        val pk = Ed25519Verifier.normalizeKey(req.publicKey)
        if (!challenges.consume(req.challenge)) throw IdentityError.InvalidChallenge()
        if (!Ed25519Verifier.verify(pk, req.signature, req.challenge.toByteArray())) throw IdentityError.BadSignature()
        val account = accounts.findByPublicKey(pk) ?: throw IdentityError.UnknownAccount()
        // Admin is granted by holding a key on the config allowlist (see class doc). pk is already
        // normalized (lowercase hex) by normalizeKey; the allowlist is normalized at config parse.
        val isAdmin = pk in adminPublicKeys
        return TokenPair(
            tokens.issueAccess(account.id, isAdmin = isAdmin),
            tokens.issueRefresh(account.id),
        )
    }

    private fun IdentityAccountRow.toView() = AccountView(id.toString(), publicKey, displayName)

    private fun isUniqueViolation(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = (cur.message ?: "").lowercase()
            if ("23505" in msg || "duplicate key" in msg || "unique constraint" in msg) return true
            cur = cur.cause
        }
        return false
    }

    companion object {
        // The exact bytes a client signs to prove key ownership at registration. Fixed literal so a
        // register signature can never be replayed as a login (which signs a live nonce instead).
        val REGISTER_MESSAGE: ByteArray = "viewrr:register".toByteArray()
    }
}
