package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #121 slice 1: [WorkletRpc] over in-memory piped streams (no `bare`/`node` needed). A fake
 * responder coroutine plays the worklet end of the pipe.
 */
class WorkletRpcTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** A pair of pipes wiring [WorkletRpc] to a fake worklet: client<->worklet in both directions. */
    private class Wiring {
        val clientToWorklet = PipedOutputStream()
        val workletIn = PipedInputStream(clientToWorklet, 8192)
        val workletToClient = PipedOutputStream()
        val clientIn = PipedInputStream(workletToClient, 8192)
    }

    @Test
    fun pingResolvesToPong() = runBlocking {
        val w = Wiring()
        // Responder: for every request line, reply pong preserving the id.
        scope.launch(Dispatchers.IO) {
            val r = w.workletIn.bufferedReader()
            val out = w.workletToClient.bufferedWriter()
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val id = json.parseToJsonElement(line).jsonObject["id"]!!.jsonPrimitive.long
                out.write("""{"id":$id,"result":"pong"}""")
                out.write("\n")
                out.flush()
            }
        }

        val rpc = WorkletRpc(w.clientIn, w.clientToWorklet, scope, defaultTimeoutMs = 2000)
        val result = rpc.call("ping")

        assertTrue(result is JsonPrimitive)
        assertEquals("pong", (result as JsonPrimitive).content)
        rpc.close()
    }

    @Test
    fun concurrentCallsCorrelateById() = runBlocking {
        val w = Wiring()
        // Buffer BOTH requests, then answer in REVERSE order — so a correct result can only come from
        // id correlation, never from positional/ordering luck. result echoes the method name.
        scope.launch(Dispatchers.IO) {
            val r = w.workletIn.bufferedReader()
            val out = w.workletToClient.bufferedWriter()
            val reqs = mutableListOf<Pair<Long, String>>()
            while (reqs.size < 2) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val obj = json.parseToJsonElement(line).jsonObject
                reqs += obj["id"]!!.jsonPrimitive.long to obj["method"]!!.jsonPrimitive.content
            }
            for ((id, method) in reqs.reversed()) {
                out.write("""{"id":$id,"result":"$method"}""")
                out.write("\n")
            }
            out.flush()
        }

        val rpc = WorkletRpc(w.clientIn, w.clientToWorklet, scope, defaultTimeoutMs = 2000)
        val alpha = async { rpc.call("alpha") }
        val beta = async { rpc.call("beta") }

        assertEquals("alpha", (alpha.await() as JsonPrimitive).content)
        assertEquals("beta", (beta.await() as JsonPrimitive).content)
        rpc.close()
    }

    @Test
    fun noResponseHitsTimeout() = runBlocking {
        val w = Wiring() // no responder is started, so the request is never answered
        val rpc = WorkletRpc(w.clientIn, w.clientToWorklet, scope, defaultTimeoutMs = 50)

        assertFailsWith<WorkletRpcException.Timeout> { rpc.call("ping", timeoutMs = 50) }
        rpc.close()
    }
}
