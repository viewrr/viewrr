package wtf.jobin.identity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Ed25519VerifierTest {

    @Test
    fun validSignatureVerifies() {
        val id = Ed25519TestIdentity()
        val msg = "viewrr:register".toByteArray()
        assertTrue(Ed25519Verifier.verify(id.publicKeyHex, id.sign(msg), msg))
    }

    @Test
    fun uppercaseKeyStillVerifies() {
        // normalizeKey lowercases before storage/compare; the verifier itself must accept either case.
        val id = Ed25519TestIdentity()
        val msg = "nonce-abc".toByteArray()
        assertTrue(Ed25519Verifier.verify(id.publicKeyHex.uppercase(), id.sign(msg), msg))
    }

    @Test
    fun tamperedSignatureFails() {
        val id = Ed25519TestIdentity()
        val msg = "hello".toByteArray()
        val sig = id.sign(msg).toCharArray().also { it[0] = if (it[0] == 'a') 'b' else 'a' }.concatToString()
        assertFalse(Ed25519Verifier.verify(id.publicKeyHex, sig, msg))
    }

    @Test
    fun wrongMessageFails() {
        val id = Ed25519TestIdentity()
        val sig = id.sign("intended".toByteArray())
        assertFalse(Ed25519Verifier.verify(id.publicKeyHex, sig, "different".toByteArray()))
    }

    @Test
    fun otherKeyCannotVerify() {
        val signer = Ed25519TestIdentity()
        val other = Ed25519TestIdentity()
        val msg = "shared".toByteArray()
        assertFalse(Ed25519Verifier.verify(other.publicKeyHex, signer.sign(msg), msg))
    }

    @Test
    fun malformedHexInputsFailClosed() {
        val id = Ed25519TestIdentity()
        val sig = id.sign("m".toByteArray())
        assertFalse(Ed25519Verifier.verify("zz", sig, "m".toByteArray())) // non-hex key
        assertFalse(Ed25519Verifier.verify(id.publicKeyHex, "abc", "m".toByteArray())) // odd-length sig
        assertFalse(Ed25519Verifier.verify("", sig, "m".toByteArray())) // empty key
        assertFalse(Ed25519Verifier.verify("00".repeat(31), sig, "m".toByteArray())) // 31-byte key
    }
}
