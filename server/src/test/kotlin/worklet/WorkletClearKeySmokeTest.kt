package wtf.jobin.worklet

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #122 (P2P-ADR 0001): proves the self-custody clear-key path over the real `bare` runtime — the
 * PRIMARY, CI-safe assertion that slices 5a/5b actually compose. Spawns `bare clearkey-selftest.mjs`,
 * which in ONE process seals a content key to an identity pubkey, opens it with that identity's secret
 * key, decrypts the frozen GOLDEN segment with the sealed-then-opened key, and asserts that an
 * outsider's key cannot open the seal and a tampered segment fails authentication — purely in-memory
 * (libsodium sealed box + XChaCha20-Poly1305), no DHT. So it is deterministic: exit 0 = the
 * content-protection path is proven, non-zero = it is broken.
 *
 * This is the first runtime assertion of the #122 invariant that the content key is delivered SEALED
 * and opened only inside the worklet — the raw key never travels in the clear. It is additive: no
 * playback route consumes the decryptor yet (route wiring behind the flag is a later increment), so
 * existing playback is untouched.
 *
 * Gates on `bare` + `worklet/node_modules` exactly like [WorkletHypercoreSmokeTest] /
 * [WorkletPairingSmokeTest]: a JVM-only CI checkout with no `bun install` skips cleanly; a dev box
 * that ran `bun install` in `worklet/` runs it.
 */
class WorkletClearKeySmokeTest {

    @Test
    fun selfCustodyClearKeyPath_isProvenDeterministically() {
        val worklet = resolveWorklet("clearkey-selftest.mjs")
            ?: return skip("could not locate worklet/clearkey-selftest.mjs")
        val runtime = resolveRuntime() ?: return skip("no `bare` runtime on PATH (set WORKLET_TEST_RUNTIME)")
        if (!depsInstalled(worklet)) return skip("worklet/node_modules missing — run `bun install` in worklet/")

        val proc = ProcessBuilder(runtime.absolutePath, worklet.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = StringBuilder()
        // Drain stdout on a side thread so a full pipe can never deadlock waitFor; the selftest also
        // self-exits within ~5s on any wedge.
        val drain = thread(isDaemon = true) { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
        val finished = proc.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw AssertionError("[clearkey-selftest] did not exit within 30s; output so far:\n$out")
        }
        drain.join(2000)
        assertEquals(0, proc.exitValue(), "[clearkey-selftest] non-zero exit — self-custody clear-key path failed:\n$out")
        println("[clearkey-selftest] OK (${runtime.name}): ${out.toString().trim()}")
    }

    private fun skip(reason: String) {
        println("[clearkey-smoke] SKIPPED: $reason")
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
     * `bare` on PATH — the worklet's native, and only, runtime (same policy as the sibling smoke tests).
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
}
