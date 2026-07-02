package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import wtf.jobin.config.AppConfig
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/**
 * #121 slice 1 (P2P-ADR 0003): owns the worklet subprocess lifecycle.
 *
 * When enabled, [start] launches a supervision loop in the caller's scope: spawn the process, wire
 * a [WorkletRpc] to its stdio, wait for it to exit, then restart with capped exponential backoff up
 * to `maxRestartAttempts`. [ping] round-trips a `ping`/`pong` over the RPC to prove liveness.
 *
 * When the feature flag is OFF (the default) nothing is spawned and [ping] returns false — so a
 * default boot is byte-identical to today. No P2P/identity/swarm logic lives here; this is pure
 * control-plane plumbing behind the flag.
 *
 * `sleeper` is injectable purely so tests assert backoff without waiting real time.
 */
class WorkletSupervisor(
    private val config: AppConfig.Worklet,
    private val spawner: ProcessSpawner,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val log = LoggerFactory.getLogger(WorkletSupervisor::class.java)

    private val rpcRef = AtomicReference<WorkletRpc?>(null)

    @Volatile
    private var superviseJob: Job? = null

    /**
     * Starts supervision if the flag is on; returns the supervision [Job], or null when disabled
     * (nothing spawned). Idempotent: a second call returns the already-running job.
     */
    fun start(scope: CoroutineScope): Job? {
        if (!config.enabled) return null
        synchronized(this) {
            superviseJob?.let { return it }
            val job = scope.launch { superviseLoop() }
            superviseJob = job
            return job
        }
    }

    private suspend fun superviseLoop() = coroutineScope {
        var restarts = 0
        while (isActive) {
            val process = try {
                spawner.spawn(config.command)
            } catch (t: Throwable) {
                log.error("worklet spawn failed: {}", t.message, t)
                restarts++
                if (restarts > config.maxRestartAttempts) {
                    log.error("worklet giving up after {} spawn attempts", restarts)
                    break
                }
                sleeper(backoffFor(restarts))
                continue
            }

            val rpc = WorkletRpc(process.stdout, process.stdin, this, config.pingTimeoutMs)
            rpcRef.set(rpc)

            val exitCode = try {
                process.awaitExit()
            } finally {
                rpcRef.set(null)
                rpc.close()
            }

            if (!isActive) break

            restarts++
            if (restarts > config.maxRestartAttempts) {
                log.error(
                    "worklet exceeded maxRestartAttempts={} (last exit={}); giving up",
                    config.maxRestartAttempts, exitCode,
                )
                break
            }
            val backoff = backoffFor(restarts)
            log.warn("worklet exited (code={}); restart #{} in {}ms", exitCode, restarts, backoff)
            sleeper(backoff)
        }
    }

    /** Sends `ping`, expects `pong`. Returns false on timeout, transport error, or when down/disabled. */
    suspend fun ping(): Boolean {
        val rpc = rpcRef.get() ?: return false
        return try {
            val result = rpc.call("ping", timeoutMs = config.pingTimeoutMs)
            (result as? JsonPrimitive)?.contentOrNull == "pong"
        } catch (e: WorkletRpcException) {
            false
        }
    }

    /** Cancels supervision and tears down any live process RPC. Safe to call when never started. */
    fun close() {
        synchronized(this) {
            superviseJob?.cancel()
            superviseJob = null
        }
        rpcRef.getAndSet(null)?.close()
    }

    // Capped exponential: base * 2^(restart-1), clamped to [0, MAX_BACKOFF_MS]. The ceiling is a
    // fixed constant, not a config knob (ponytail: no extra surface for slice 1).
    private fun backoffFor(restart: Int): Long {
        if (config.backoffBaseMs <= 0L) return 0L
        val scaled = config.backoffBaseMs.toDouble() * 2.0.pow((restart - 1).coerceAtLeast(0))
        return scaled.toLong().coerceIn(0L, MAX_BACKOFF_MS)
    }

    private companion object {
        const val MAX_BACKOFF_MS = 30_000L
    }
}
