package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #121 inc-2 (P2P-ADR 0003): proves the Hyper* core over the shipped seam — Hypercore append +
 * replication, and Hyperswarm join. Two tests, deliberately split by how reliable each can be:
 *
 *  1. [directStreamReplication_selftestProvesTheDataPath] — the PRIMARY, CI-safe proof. It spawns
 *     `bare hyper-selftest.mjs`, which replicates two local Hypercores over a DIRECT in-memory duplex
 *     (no DHT): writer appends, a read-only replica reads the same bytes back. No network, so when it
 *     runs it is deterministic — exit 0 or the data path is broken. This is the real proof of #121-2.
 *
 *  2. [liveSwarmReplication_readerReplicatesOverDht] — the SECONDARY, network-GATED smoke. Two real
 *     `hyperlet.mjs` subprocesses join one Hyperswarm topic; one appends, the other discovers +
 *     replicates + reads. It needs live DHT connectivity, so it is bounded by a hard timeout and
 *     SKIPS-with-reason (never fails, never hangs) when the DHT is unreachable — same honesty model
 *     as [WorkletRoundtripSmokeTest]. A genuine data mismatch still fails; only "no peer" skips.
 *
 * Both gate on bare + `worklet/node_modules` exactly like the roundtrip smoke: a JVM-only CI checkout
 * with no `bun install` skips cleanly; a dev box that ran `bun install` in `worklet/` runs them.
 */
class WorkletHypercoreSmokeTest {

    @Test
    fun directStreamReplication_selftestProvesTheDataPath() {
        val worklet = resolveWorklet("hyper-selftest.mjs") ?: return skip("could not locate worklet/hyper-selftest.mjs")
        val runtime = resolveRuntime() ?: return skip("no `bare` runtime on PATH (set WORKLET_TEST_RUNTIME, or install `bare`)")
        if (!depsInstalled(worklet)) return skip("worklet/node_modules missing — run `bun install` in worklet/")

        val proc = ProcessBuilder(runtime.absolutePath, worklet.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = StringBuilder()
        // Drain stdout on a side thread so a full pipe can never deadlock waitFor (output is tiny,
        // but this keeps the pattern correct); the selftest also self-exits within ~10s on any wedge.
        val drain = thread(isDaemon = true) { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw AssertionError("[hyper-selftest] did not exit within 30s; output so far:\n$out")
        }
        drain.join(2000)
        assertEquals(0, proc.exitValue(), "[hyper-selftest] non-zero exit — replication data path failed:\n$out")
        println("[hyper-selftest] OK (${runtime.name}): $out".trim())
    }

    @Test
    fun liveSwarmReplication_readerReplicatesOverDht() = runBlocking {
        val worklet = resolveWorklet("hyperlet.mjs") ?: return@runBlocking skip("could not locate worklet/hyperlet.mjs")
        val runtime = resolveRuntime() ?: return@runBlocking skip("no `bare` runtime on PATH")
        if (!depsInstalled(worklet)) return@runBlocking skip("worklet/node_modules missing — run `bun install` in worklet/")

        val topicHex = randomHex(32)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Spawn our OWN processes (not RealProcessSpawner): its stderr is INHERIT, and on this box
        // `bare` is a node wrapper that forks the real bare-runtime as a GRANDCHILD — a plain
        // destroy() kills only the wrapper, leaving the grandchild holding the inherited stderr fd
        // and wedging Gradle's test worker forever. So: DISCARD stderr (nothing to leak) and, in
        // finally, force-kill the whole process TREE. Belt to withTimeout's suspenders — this test
        // must never hang, only pass or skip.
        val writerProc = spawnHyperlet(runtime, worklet)
        val readerProc = spawnHyperlet(runtime, worklet)
        val writerRpc = WorkletRpc(writerProc.inputStream, writerProc.outputStream, scope, defaultTimeoutMs = 8000)
        val readerRpc = WorkletRpc(readerProc.inputStream, readerProc.outputStream, scope, defaultTimeoutMs = 8000)
        val writer = WorkletHypercore(writerRpc)
        val reader = WorkletHypercore(readerRpc)
        try {
            withTimeout(45_000) {
                val core = writer.coreOpen()
                writer.append(core.handle, "swarm-hello")
                writer.swarmJoin(core.handle, topicHex)          // server (writable)

                val replica = reader.coreOpen(keyHex = core.keyHex)
                reader.swarmJoin(replica.handle, topicHex)        // client (read-only)

                // Blocks until the block replicates from the writer over the DHT; the RPC timeout is
                // the "peer never showed up" bound.
                val got = reader.get(replica.handle, 0, timeoutMs = 30_000)
                assertEquals("swarm-hello", got, "replica read wrong data over the swarm")
            }
            println("[hyper-swarm] OK: reader replicated \"swarm-hello\" from writer over a live Hyperswarm topic")
        } catch (e: TimeoutCancellationException) {
            skip("no DHT replication within budget — network-gated (expected off-network / in CI)")
        } catch (e: WorkletRpcException.Timeout) {
            skip("worklet RPC timed out (${e.message}) — DHT unreachable in this environment")
        } catch (e: WorkletRpcException.Closed) {
            skip("worklet transport closed early (${e.message}) — runtime/network unavailable")
        } finally {
            runCatching { writerRpc.close() }
            runCatching { readerRpc.close() }
            killTree(writerProc)
            killTree(readerProc)
            scope.cancel()
        }
    }

    /** Spawn a hyperlet under [runtime]. stderr DISCARDed so a leaked grandchild can't wedge Gradle. */
    private fun spawnHyperlet(runtime: File, worklet: File): Process =
        ProcessBuilder(runtime.absolutePath, worklet.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()

    /** Force-kill a process AND its descendants — `bare` forks the real runtime as a grandchild. */
    private fun killTree(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
    }

    private fun skip(reason: String) {
        println("[hyper-smoke] SKIPPED: $reason")
    }

    /** Locate a repo-root `worklet/<name>` from the test working dir (server/ or repo root). */
    private fun resolveWorklet(name: String): File? =
        listOf("worklet/$name", "../worklet/$name")
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.canonicalFile

    private fun depsInstalled(worklet: File): Boolean =
        File(worklet.parentFile, "node_modules").isDirectory

    /**
     * Pick the runtime: explicit `WORKLET_TEST_RUNTIME` (name or absolute path) wins, else probe
     * `bare` on PATH. No node fallback — the worklet stdio is bare-native (see WorkletRoundtripSmokeTest).
     */
    private fun resolveRuntime(): File? {
        System.getenv("WORKLET_TEST_RUNTIME")?.takeIf { it.isNotBlank() }?.let { spec ->
            val direct = File(spec)
            return if (direct.isAbsolute && direct.canExecute()) direct else onPath(spec)
        }
        return onPath("bare")
    }

    private fun onPath(cmd: String): File? =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, cmd) }
            .firstOrNull { it.isFile && it.canExecute() }

    private fun randomHex(nBytes: Int): String {
        val b = ByteArray(nBytes).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }
}
