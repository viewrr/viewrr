package wtf.jobin.identity

import wtf.jobin.auth.TokenPair
import wtf.jobin.auth.TokenService

sealed class IdentityError(msg: String) : RuntimeException(msg) {
    class BadSignature : IdentityError("signature does not verify")
    class InvalidChallenge : IdentityError("challenge is unknown, expired, or already used")
    class UnknownAccount : IdentityError("public key is not registered")
}

/**
 * #120 self-custody identity, foundation increment 1. Proves control of an Ed25519 key pair
 * WITHOUT Keycloak, then issues the app's existing JWT/refresh session (TokenService) keyed by
 * the account's internal UUID — so every downstream route works unchanged.
 *
 * ponytail: identities are non-admin. There is no key->admin promotion path in this increment;
 * admin stays with the legacy user/Keycloak surface until the cutover follow-up.
 */
class IdentityService(
    private val accounts: IdentityAccountRepository,
    private val challenges: ChallengeStore,
    private val tokens: TokenService,
) {
    /** Register (or idempotently re-hit) an account. Returns whether the row was freshly created. */
    suspend fun register(req: RegisterIdentityRequest): Pair<AccountView, Boolean> {
        val pk = Ed25519Verifier.normalizeKey(req.publicKey)
        if (!Ed25519Verifier.verify(pk, req.signature, REGISTER_MESSAGE)) throw IdentityError.BadSignature()

        accounts.findByPublicKey(pk)?.let { return AccountView(it.id.toString(), it.publicKey) to false }
        val row = try {
            accounts.create(pk)
        } catch (e: Exception) {
            // Lost a create race: another request inserted the same key between our lookup and insert.
            if (isUniqueViolation(e)) {
                val existing = accounts.findByPublicKey(pk) ?: throw e
                return AccountView(existing.id.toString(), existing.publicKey) to false
            }
            throw e
        }
        return AccountView(row.id.toString(), row.publicKey) to true
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
        return TokenPair(
            tokens.issueAccess(account.id, isAdmin = false),
            tokens.issueRefresh(account.id),
        )
    }

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
