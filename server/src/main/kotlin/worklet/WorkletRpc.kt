package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * #121 slice 1 (P2P-ADR 0003): the worklet control channel.
 *
 * Newline-delimited JSON-RPC (LSP-style) over the worklet subprocess's stdin/stdout — plain
 * process pipes, NOT a socket. Requests are `{"id":N,"method":..,"params":..}\n`; responses are
 * `{"id":N,"result":..}` or `{"id":N,"error":..}`, correlated back to the caller by integer id so
 * multiple calls can be in flight at once.
 *
 * This class owns codec + correlation only — it spawns no process, it takes the streams. That keeps
 * it unit-testable with in-memory pipes and lets [WorkletSupervisor] wire it to a real process.
 *
 * ponytail: stdio is the whole transport for slice 1. A Unix-domain-socket *data* channel is
 * deferred to slice 5, and only if bulk HLS-segment transfer proves it needs one — the control
 * plane never will.
 */
class WorkletRpc(
    input: InputStream,
    output: OutputStream,
    scope: CoroutineScope,
    private val defaultTimeoutMs: Long,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(WorkletRpc::class.java)

    // Keep the raw streams: close() closes THESE (unblocking a real process's reader on EOF), never
    // the buffered wrappers below — a blocked readLine() holds the BufferedReader's internal lock,
    // so calling reader.close() from another thread would deadlock against it.
    private val rawInput = input
    private val rawOutput = output
    private val reader = input.bufferedReader()
    private val writer = output.bufferedWriter()

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()

    // Serializes concurrent writers so two in-flight calls never interleave bytes on one line.
    private val writeMutex = Mutex()

    // Reads response lines for the process's whole lifetime. Blocking readLine lives on IO.
    private val readerJob: Job = scope.launch(Dispatchers.IO) { readLoop() }

    private suspend fun readLoop() {
        try {
            while (true) {
                val line = reader.readLine() ?: break // EOF: process closed its stdout
                if (line.isBlank()) continue
                dispatch(line)
            }
        } catch (e: IOException) {
            // Stream closed under us (process died / close()) — fall through to fail pending calls.
            log.debug("worklet rpc read loop ended: {}", e.message)
        } finally {
            failPending(WorkletRpcException.Closed("<read-loop-ended>"))
        }
    }

    private fun dispatch(line: String) {
        val obj: JsonObject = try {
            json.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
            log.warn("worklet rpc: dropping unparseable line: {}", line)
            return
        }
        val id = obj["id"]?.jsonPrimitive?.longOrNull
        if (id == null) {
            log.warn("worklet rpc: dropping response with no integer id: {}", line)
            return
        }
        val deferred = pending.remove(id)
        if (deferred == null) {
            // Late response after a timeout, or an id we never issued — nothing to resolve.
            log.debug("worklet rpc: no pending call for id {}", id)
            return
        }
        val error = obj["error"]
        if (error != null && error != JsonNull) {
            deferred.completeExceptionally(WorkletRpcException.Remote("<id=$id>", error))
        } else {
            deferred.complete(obj["result"] ?: JsonNull)
        }
    }

    /**
     * Sends [method] with optional [params] and suspends for the correlated response.
     * Throws [WorkletRpcException.Timeout] if no response arrives within [timeoutMs],
     * [WorkletRpcException.Remote] if the worklet returns an error, or
     * [WorkletRpcException.Closed] if the transport is gone.
     */
    suspend fun call(
        method: String,
        params: JsonElement? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred

        val request = buildJsonObject {
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

        try {
            writeLine(json.encodeToString(JsonObject.serializer(), request))
        } catch (e: IOException) {
            pending.remove(id)
            throw WorkletRpcException.Closed(method)
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw WorkletRpcException.Timeout(method, timeoutMs)
        }
    }

    private suspend fun writeLine(line: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    private fun failPending(cause: WorkletRpcException) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            it.remove()
            entry.value.completeExceptionally(cause)
        }
    }

    override fun close() {
        readerJob.cancel()
        failPending(WorkletRpcException.Closed("<closed>"))
        // Close the RAW streams, not the buffered wrappers: a parked readLine() holds the
        // BufferedReader's monitor, so reader.close() would block behind it (the deadlock).
        // Closing the underlying stream forces that readLine() to throw and the loop to exit.
        runCatching { rawInput.close() }
        runCatching { rawOutput.close() }
    }
}

/** Failure modes of a worklet RPC call. Callers (e.g. ping) decide how to react. */
sealed class WorkletRpcException(message: String) : Exception(message) {
    class Timeout(method: String, timeoutMs: Long) :
        WorkletRpcException("worklet RPC '$method' timed out after ${timeoutMs}ms")

    class Remote(method: String, val error: JsonElement) :
        WorkletRpcException("worklet RPC '$method' returned error: $error")

    class Closed(method: String) :
        WorkletRpcException("worklet RPC '$method' failed: transport closed")
}
