package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #121 slice 1 (P2P-ADR 0003): the *real-runtime* roundtrip smoke test.
 *
 * The sibling tests ([WorkletRpcTest], [WorkletSupervisorTest]) prove the seam against in-memory
 * pipes and a fake spawner — fast and deterministic, but they never launch an actual runtime. This
 * test closes that gap end-to-end: it spawns the shipped `worklet/ping.mjs` as a real subprocess via
 * the shipped [RealProcessSpawner], drives the shipped [WorkletRpc] over the child's stdio, calls
 * `ping`, asserts `pong`, then tears the process down cleanly. That is the "JVM ↔ worklet" seam
 * proven against a real JS runtime, not a mock.
 *
 * Runtime selection (env `WORKLET_TEST_RUNTIME` wins, else auto): the shipped `ping.mjs` uses Node's
 * `process.stdin/stdout` globals, so it runs today under **node**. It does NOT yet run under **bare**
 * (`ReferenceError: process is not defined` — Bare exposes stdio via `bare-pipe`/`bare-process`, not
 * a Node `process` global). Porting the worklet's stdio to Bare's API is a worklet-code change owned
 * by a later slice, out of scope for this test-only increment.
 * ponytail: default the probe to node (the runtime the shipped worklet actually supports); once the
 * worklet stdio is Bare-native, flip the default to `bare` and this same test proves it unchanged.
 *
 * Gating (honesty over a green checkmark, per #121): when no usable runtime is on PATH, or the
 * worklet's JS deps aren't installed (`worklet/node_modules` — `ping.mjs` imports hypercore-crypto
 * transitively via identity.mjs), the test SKIPS with a loud reason instead of failing or hanging.
 * A JVM-only CI checkout with no `bun install` in `worklet/` therefore skips cleanly; a dev box that
 * ran `bun install` in `worklet/` runs it for real. The RPC call carries a hard timeout so a wedged
 * child can never hang the suite.
 */
class WorkletRoundtripSmokeTest {

    @Test
    fun realSubprocessPingRoundtrips() = runBlocking {
        val worklet = resolveWorkletJs()
        if (worklet == null) {
            println("[worklet-roundtrip] SKIPPED: could not locate worklet/ping.mjs from ${File(".").absolutePath}")
            return@runBlocking
        }
        val deps = File(worklet.parentFile, "node_modules")
        if (!deps.isDirectory) {
            println("[worklet-roundtrip] SKIPPED: ${deps.path} missing — run `bun install` in worklet/ (ping.mjs imports hypercore-crypto)")
            return@runBlocking
        }
        val runtime = resolveRuntime()
        if (runtime == null) {
            println("[worklet-roundtrip] SKIPPED: no worklet runtime on PATH (set WORKLET_TEST_RUNTIME, or install `node`/`bare`)")
            return@runBlocking
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val spawner = RealProcessSpawner()
        val process = spawner.spawn(listOf(runtime.absolutePath, worklet.absolutePath))
        val rpc = WorkletRpc(process.stdout, process.stdin, scope, defaultTimeoutMs = 8000)
        try {
            // params are carried across the seam even though the shipped `ping` ignores them; this
            // asserts the request encoder round-trips a params object without upsetting correlation.
            val result = rpc.call("ping", buildJsonObject { put("nonce", "rt-1") }, timeoutMs = 8000)
            assertTrue(result is JsonPrimitive, "expected a JSON primitive result, got $result")
            assertEquals("pong", result.content, "worklet did not answer ping with pong")
            println("[worklet-roundtrip] OK: ${runtime.name} ran ${worklet.name} — ping->pong over real subprocess stdio")
        } finally {
            rpc.close()
            process.destroy()   // clean stop: no awaitExit (avoid hang); destroy signals the child
            scope.cancel()
        }
    }

    /** Locate the repo-root `worklet/ping.mjs` from the test working dir (server/ or repo root). */
    private fun resolveWorkletJs(): File? =
        listOf("worklet/ping.mjs", "../worklet/ping.mjs")
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.canonicalFile

    /**
     * Pick a runtime binary. Explicit `WORKLET_TEST_RUNTIME` wins (name or absolute path); otherwise
     * probe node first (shipped-worklet compatible), then bare. Returns null if none is executable.
     */
    private fun resolveRuntime(): File? {
        System.getenv("WORKLET_TEST_RUNTIME")?.takeIf { it.isNotBlank() }?.let { spec ->
            val direct = File(spec)
            return if (direct.isAbsolute && direct.canExecute()) direct else onPath(spec)
        }
        return onPath("node") ?: onPath("bare")
    }

    /** First executable named [cmd] on PATH, or null. */
    private fun onPath(cmd: String): File? =
        (System.getenv("PATH") ?: "").split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, cmd) }
            .firstOrNull { it.isFile && it.canExecute() }
}
