package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #126 (P2P-ADR 0006): proves the two private-discovery capabilities over the shipped worklet seam.
 * Two tests, deliberately split by reliability — same honesty model as [WorkletHypercoreSmokeTest]:
 *
 * 1. [privateTopicDerivation_isDeterministic] — PRIMARY, CI-safe proof. Spawns `bare
 *    topic-selftest.mjs`, which asserts IN-PROCESS (keyed BLAKE2b, no network) that the same
 *    secret+label always derives the same topic, a different secret derives a different topic, and a
 *    different label derives a different topic. No DHT, so it is deterministic — exit 0 = proven,
 *    non-zero = the derivation is broken. This is the real #126 proof.
 *
 * 2. [vaultLinkPairing_transfersOverEphemeralChannel] — SECONDARY, network-GATED smoke. Two real
 *    `pairlet.mjs` subprocesses run a Vault Link: the host mints a one-time pairing secret and joins
 *    hash(secret); the new device joins the SAME topic from that secret, and the encrypted vault
 *    crosses the Noise-encrypted Hyperswarm channel; both then tear the ephemeral topic down. Needs
 *    live DHT connectivity, so it is bounded by hard timeouts and SKIPS-with-reason (never fails,
 *    never hangs) when the DHT is unreachable. A genuine vault MISMATCH still fails; only "no peer"
 *    skips.
 *
 * Both gate on `bare` + `worklet/node_modules` exactly like the JVM-only-CI checkout: no runtime or
 * no `bun install` in `worklet/` → skip cleanly. Live processes are force-killed by TREE in `finally`
 * because `bare` forks a real runtime grandchild (see [WorkletHypercoreSmokeTest] for the rationale).
 */
class WorkletPairingSmokeTest {

    private val vaultHex = "deadbeefcafe" + "00".repeat(26) // 32-byte opaque "encrypted vault" blob

    @Test
    fun privateTopicDerivation_isDeterministic() {
        val worklet = resolveWorklet("topic-selftest.mjs")
            ?: return skip("could not locate worklet/topic-selftest.mjs")
        val runtime = resolveRuntime() ?: return skip("no `bare` runtime on PATH (set WORKLET_TEST_RUNTIME)")
        if (!depsInstalled(worklet)) return skip("worklet/node_modules missing — run `bun install` in worklet/")

        val proc = ProcessBuilder(runtime.absolutePath, worklet.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()
        val out = StringBuilder()
        val drain = thread(isDaemon = true) { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw AssertionError("[topic-selftest] did not exit within 20s; output so far:\n$out")
        }
        drain.join(2000)
        assertEquals(0, proc.exitValue(), "[topic-selftest] non-zero exit — private-topic derivation broken:\n$out")
        println("[topic-selftest] OK (${runtime.name}): ${out.toString().trim()}")
    }

    @Test
    fun vaultLinkPairing_transfersOverEphemeralChannel() = runBlocking {
        val worklet = resolveWorklet("pairlet.mjs") ?: return@runBlocking skip("could not locate worklet/pairlet.mjs")
        val runtime = resolveRuntime() ?: return@runBlocking skip("no `bare` runtime on PATH")
        if (!depsInstalled(worklet)) return@runBlocking skip("worklet/node_modules missing — run `bun install` in worklet/")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Spawn our OWN processes (not RealProcessSpawner): DISCARD stderr so a leaked bare grandchild
        // can't wedge Gradle's test worker on an inherited fd, and force-kill the whole TREE in finally.
        val hostProc = spawnPairlet(runtime, worklet)
        val joinerProc = spawnPairlet(runtime, worklet)
        val hostRpc = WorkletRpc(hostProc.inputStream, hostProc.outputStream, scope, defaultTimeoutMs = 15000)
        val joinerRpc = WorkletRpc(joinerProc.inputStream, joinerProc.outputStream, scope, defaultTimeoutMs = 15000)
        val host = WorkletPairing(hostRpc)
        val joiner = WorkletPairing(joinerRpc)
        try {
            // pairBegin's flushed() awaits the DHT announce — on a cold DHT node that can take ~15s, so
            // give it real headroom (a direct two-process roundtrip on this stack measured ~16s total).
            val session = host.pairBegin(vaultHex, timeoutMs = 30000)
            // Join + finish run concurrently (both block on a DHT peer). coroutineScope bounds them as a
            // unit: if one times out it cancels the sibling and rethrows, which we translate to a skip.
            val (received, delivered) = coroutineScope {
                val joinDef = async { joiner.pairJoin(session.pairingSecretHex, timeoutMs = 60000) }
                val finDef = async { host.pairFinish(timeoutMs = 60000) }
                joinDef.await() to finDef.await()
            }
            assertEquals(vaultHex, received, "new device received a DIFFERENT vault than the host sent")
            assertTrue(delivered, "host reported the vault was not delivered")
            println("[vault-link] OK: vault crossed the ephemeral Noise channel on topic ${session.topicHex.take(12)}…")
        } catch (e: TimeoutCancellationException) {
            skip("no DHT pairing within budget — network-gated (expected off-network / in CI)")
        } catch (e: WorkletRpcException.Timeout) {
            skip("worklet RPC timed out (${e.message}) — DHT unreachable in environment")
        } catch (e: WorkletRpcException.Closed) {
            skip("worklet transport closed early (${e.message}) — runtime/network unavailable")
        } finally {
            runCatching { hostRpc.close() }
            runCatching { joinerRpc.close() }
            killTree(hostProc)
            killTree(joinerProc)
            scope.cancel()
        }
    }

    /** Spawn a pairlet under [runtime]. stderr DISCARDed so a leaked grandchild can't wedge Gradle. */
    private fun spawnPairlet(runtime: File, worklet: File): Process =
        ProcessBuilder(runtime.absolutePath, worklet.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .start()

    /** Force-kill a process AND its descendants — `bare` forks a real runtime grandchild. */
    private fun killTree(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        runCatching { process.destroyForcibly() }
    }

    private fun skip(reason: String) {
        println("[pairing-smoke] SKIPPED: $reason")
    }

    /** Locate repo-root `worklet/<name>` from the test working dir (server/ or repo root). */
    private fun resolveWorklet(name: String): File? =
        listOf("worklet/$name", "../worklet/$name")
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.canonicalFile

    private fun depsInstalled(worklet: File): Boolean =
        File(worklet.parentFile, "node_modules").isDirectory

    /**
     * Pick a runtime binary. Explicit `WORKLET_TEST_RUNTIME` wins (name or absolute path); otherwise
     * probe `bare` — the worklet's native, and only, runtime (same policy as the sibling smoke tests).
     */
    private fun resolveRuntime(): File? {
        System.getenv("WORKLET_TEST_RUNTIME")?.takeIf { it.isNotBlank() }?.let { spec ->
            val direct = File(spec)
            return if (direct.isAbsolute && direct.canExecute()) direct else onPath(spec)
        }
        return onPath("bare")
    }

    /** First executable named [cmd] on PATH, or null. */
    private fun onPath(cmd: String): File? =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, cmd) }
            .firstOrNull { it.isFile && it.canExecute() }
}
