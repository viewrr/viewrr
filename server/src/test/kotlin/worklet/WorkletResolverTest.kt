package wtf.jobin.worklet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** #121 slice 4: resolvePeers sends the right lookup params and parses peers; degrades to empty when down. */
class WorkletResolverTest {
    @Test
    fun sendsContentUuidAndParsesPeers() = runBlocking {
        var capturedMethod: String? = null
        var capturedParams: JsonElement? = null
        val resolver = WorkletResolver(
            callWorklet = { method, params ->
                capturedMethod = method
                capturedParams = params
                buildJsonObject {
                    put("peers", buildJsonArray { add("deadbeef"); add("cafef00d") })
                }
            },
        )

        val peers = resolver.resolvePeers("bc592db3805a58ff9f95b90687681997")

        assertEquals("lookup", capturedMethod)
        assertEquals(
            "bc592db3805a58ff9f95b90687681997",
            (capturedParams as JsonObject)["contentUuid"]!!.jsonPrimitive.content,
        )
        assertEquals(listOf("deadbeef", "cafef00d"), peers)
    }

    @Test
    fun workletDownReturnsEmpty() = runBlocking {
        val resolver = WorkletResolver(callWorklet = { _, _ -> null })
        assertEquals(emptyList(), resolver.resolvePeers("bc592db3805a58ff9f95b90687681997"))
    }
}
