package wtf.jobin.identity

import java.security.KeyPairGenerator
import java.security.Signature

/**
 * Real Ed25519 key pair for tests, using the same JDK-native provider the production verifier
 * uses. The raw 32-byte public key is the tail of the X.509 SPKI encoding.
 */
class Ed25519TestIdentity {
    private val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    val publicKeyHex: String =
        toHex(kp.public.encoded.copyOfRange(kp.public.encoded.size - 32, kp.public.encoded.size))

    fun sign(message: ByteArray): String {
        val s = Signature.getInstance("Ed25519")
        s.initSign(kp.private)
        s.update(message)
        return toHex(s.sign())
    }

    fun sign(message: String): String = sign(message.toByteArray())

    companion object {
        fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    }
}
