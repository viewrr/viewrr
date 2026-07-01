package wtf.jobin.identity

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 signature verification on pure JDK crypto (JEP 339, native since JDK 15; runtime is
 * JVM 21). NO Bouncy Castle, NO third-party crypto dependency — the whole point of #120's
 * self-custody model is that the Hub only ever verifies signatures against a public key.
 *
 * Wire format: public keys and signatures are lowercase hex. A public key is the raw 32-byte
 * Ed25519 key; a signature is the raw 64-byte value. The JDK's KeyFactory has no raw-key
 * importer, so we wrap the 32 bytes in the fixed Ed25519 SubjectPublicKeyInfo (SPKI) DER prefix
 * and decode via X509EncodedKeySpec (RFC 8410 §4).
 */
object Ed25519Verifier {
    // DER SPKI header for an Ed25519 public key:
    //   SEQUENCE(42) { SEQUENCE(5){ OID 1.3.101.112 }, BIT STRING(33){ 00 <32 key bytes> } }
    // Prepending this to the raw 32-byte key yields a 44-byte SubjectPublicKeyInfo.
    private val SPKI_PREFIX = byteArrayOf(
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    )
    private const val RAW_KEY_LEN = 32
    private const val SIG_LEN = 64

    /** True iff [signatureHex] is a valid Ed25519 signature of [message] under [publicKeyHex]. */
    fun verify(publicKeyHex: String, signatureHex: String, message: ByteArray): Boolean {
        val key = decodeHex(publicKeyHex) ?: return false
        val sig = decodeHex(signatureHex) ?: return false
        if (key.size != RAW_KEY_LEN || sig.size != SIG_LEN) return false
        return try {
            val pub = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(SPKI_PREFIX + key))
            Signature.getInstance("Ed25519").run {
                initVerify(pub)
                update(message)
                verify(sig)
            }
        } catch (_: Exception) {
            // Malformed key/point or provider rejection — a failed verification, never a throw.
            false
        }
    }

    /** Lowercase-normalizing hex decode; null on odd length or any non-hex char. */
    fun decodeHex(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val hi = Character.digit(s[i], 16)
            val lo = Character.digit(s[i + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }

    /** Canonical storage/compare form for a public key. UNIQUE index relies on this. */
    fun normalizeKey(publicKeyHex: String): String = publicKeyHex.lowercase()
}
