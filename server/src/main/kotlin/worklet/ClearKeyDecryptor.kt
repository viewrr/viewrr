package wtf.jobin.worklet

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * #121 slice 5a / #122 (P2P-ADR 0001): drives in-worklet self-custody segment decryption.
 *
 * The content key never crosses the seam outward — [setContentKey] hands it in (increment 5b will
 * instead hand in a pubkey-sealed blob the worklet opens itself), [decryptSegment] gets plaintext
 * back. Read-only capability: no route consumes it yet, so playback is unchanged (increment 5c
 * wires it behind the flag). Same testable `callWorklet` lambda pattern as WorkletResolver.
 */
class ClearKeyDecryptor(
    private val callWorklet: suspend (method: String, params: JsonElement?) -> JsonElement?,
) {
    /** Bootstrap the worklet's identity keypair from a seed. False when down/disabled/rejected. */
    suspend fun loadIdentity(seedHex: String): Boolean {
        val params = buildJsonObject { put("seedHex", seedHex) }
        val result = callWorklet("loadIdentity", params) as? JsonObject ?: return false
        return result["publicKey"]?.jsonPrimitive?.content != null
    }

    /**
     * Hand the worklet a content key SEALED to its identity pubkey (+ 16-byte base nonce). The
     * worklet opens it with its own secret key; the raw key never crosses the seam. Requires a
     * prior [loadIdentity]. False when down/disabled or the seal fails to open.
     */
    suspend fun openContentKey(sealedHex: String, baseNonceHex: String): Boolean {
        val params = buildJsonObject { put("sealedHex", sealedHex); put("baseNonceHex", baseNonceHex) }
        val result = callWorklet("openContentKey", params) as? JsonObject ?: return false
        return result["ok"]?.jsonPrimitive?.booleanOrNull == true
    }

    /** Decrypt one segment. Null when the worklet is down/disabled, no key is set, or auth fails. */
    suspend fun decryptSegment(segIndex: Int, cipherHex: String): ByteArray? {
        val params = buildJsonObject { put("segIndex", segIndex); put("cipherHex", cipherHex) }
        val result = callWorklet("decryptSegment", params) as? JsonObject ?: return null
        val hex = result["plaintextHex"]?.jsonPrimitive?.content ?: return null
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
