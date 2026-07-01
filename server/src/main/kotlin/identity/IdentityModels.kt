package wtf.jobin.identity

import kotlinx.serialization.Serializable

// #120 wire DTOs. Keys and signatures are lowercase hex (see Ed25519Verifier).

/** POST /identity/register — signature is Ed25519(REGISTER_MESSAGE) by the account's secret key. */
@Serializable
data class RegisterIdentityRequest(val publicKey: String, val signature: String)

/** GET /identity/challenge response. */
@Serializable
data class ChallengeResponse(val challenge: String)

/**
 * POST /identity/verify — proves ownership of [publicKey] by signing a live [challenge].
 * ponytail: the challenge is echoed back in the body (not just {publicKey,signature} as the
 * issue sketch shows) so the Hub can bind + single-use-consume the exact nonce without a
 * per-connection session. Standard challenge-response wire shape.
 */
@Serializable
data class VerifyIdentityRequest(val publicKey: String, val challenge: String, val signature: String)

/** Returned by register — the created/existing account, without any secret material. */
@Serializable
data class AccountView(val accountId: String, val publicKey: String)
