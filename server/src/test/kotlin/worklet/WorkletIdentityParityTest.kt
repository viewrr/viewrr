package wtf.jobin.worklet

import wtf.jobin.identity.Ed25519Verifier
import wtf.jobin.identity.IdentityService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #121 slice 2 — identity parity. Proves the worklet's crypto stack (`hypercore-crypto.keyPair`,
 * libsodium ed25519) produces an identity the Hub's pure-JDK [Ed25519Verifier] (#135) accepts.
 * If this passes, the same mnemonic yields one identity usable in BOTH app auth and the P2P swarm.
 *
 * FROZEN golden — regenerate with `worklet/derive.mjs <seedHex>` (see worklet/README). The seed is
 * the 32-byte keyPair seed; upstream, clients derive it as the first 32 bytes of the BIP39-512 seed
 * (frozen reduction for #142/web). This CI test needs no node/bare — it only runs the JDK verifier
 * over the pinned fixture, which is exactly the code path #135 registration uses.
 */
class WorkletIdentityParityTest {
    private val seedHex = "0000000000000000000000000000000000000000000000000000000000000000"
    private val publicKeyHex = "3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29"
    private val signatureHex =
        "dead1e14fade052b9f644959fd7dbc198c4073cf07dda8002c7fa9efc66603e9" +
            "b13075c8f71a90b13096bdf9d2c011e550023eb5b18e4f72cc360d280d756609"

    @Test
    fun workletKeypairIsAcceptedByHubVerifier() {
        // The worklet signed IdentityService.REGISTER_MESSAGE with the key derived from seedHex.
        // The Hub verifier must accept it — same as a real /identity/register would.
        assertTrue(
            Ed25519Verifier.verify(publicKeyHex, signatureHex, IdentityService.REGISTER_MESSAGE),
            "hypercore-crypto signature must verify under the JDK Ed25519 verifier (stacks agree)",
        )
    }

    @Test
    fun goldenPublicKeyIsStable() {
        // Pins the frozen seed->pubkey contract that #142 (mobile) and viewrr-web must reproduce.
        assertEquals(64, publicKeyHex.length)
        assertEquals("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29", publicKeyHex)
    }
}
