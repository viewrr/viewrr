package wtf.jobin.worklet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #121 slice 5a: decryptor sends the right RPC params, parses plaintext, and degrades to null when down. */
class ClearKeyDecryptorTest {
    @Test
    fun loadIdentitySendsSeedAndParsesPublicKey() = runBlocking {
        var captured: Pair<String, JsonElement?>? = null
        val dec = ClearKeyDecryptor { m, p -> captured = m to p; buildJsonObject { put("publicKey", "3b6a") } }

        assertTrue(dec.loadIdentity("00".repeat(32)))
        assertEquals("loadIdentity", captured!!.first)
        assertEquals("00".repeat(32), (captured!!.second as JsonObject)["seedHex"]!!.jsonPrimitive.content)
    }

    @Test
    fun openContentKeySendsSealedBlobAndParsesOk() = runBlocking {
        var captured: Pair<String, JsonElement?>? = null
        val dec = ClearKeyDecryptor { m, p -> captured = m to p; buildJsonObject { put("ok", true) } }

        assertTrue(dec.openContentKey("beef".repeat(20), "00".repeat(16)))
        assertEquals("openContentKey", captured!!.first)
        val params = captured!!.second as JsonObject
        assertEquals("beef".repeat(20), params["sealedHex"]!!.jsonPrimitive.content)
        assertEquals("00".repeat(16), params["baseNonceHex"]!!.jsonPrimitive.content)
    }

    @Test
    fun decryptSegmentSendsParamsAndDecodesPlaintextHex() = runBlocking {
        var captured: Pair<String, JsonElement?>? = null
        val dec = ClearKeyDecryptor { m, p ->
            captured = m to p
            buildJsonObject { put("plaintextHex", "68656c6c6f") } // "hello"
        }

        val out = dec.decryptSegment(7, "deadbeef")
        assertEquals("hello", out!!.decodeToString())
        assertEquals("decryptSegment", captured!!.first)
        val params = captured!!.second as JsonObject
        assertEquals(7, params["segIndex"]!!.jsonPrimitive.content.toInt())
        assertEquals("deadbeef", params["cipherHex"]!!.jsonPrimitive.content)
    }

    @Test
    fun workletDownYieldsNullAndFalse() = runBlocking {
        val dec = ClearKeyDecryptor { _, _ -> null }
        assertNull(dec.decryptSegment(0, "00"))
        assertTrue(!dec.loadIdentity("00".repeat(32)))
        assertTrue(!dec.openContentKey("beef".repeat(20), "00".repeat(16)))
    }
}
