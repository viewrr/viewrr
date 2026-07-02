package wtf.jobin.worklet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import wtf.jobin.config.AppConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #121 slice 1: [WorkletSupervisor] lifecycle, driven by a fake spawner (no real process, no
 * `bare`/`node`). Backoff is asserted via an injected instant `sleeper`, and process exit is
 * driven by completing a [CompletableDeferred].
 */
class WorkletSupervisorTest {

    /** A fake worklet process with empty (immediate-EOF) stdio and a caller-driven exit. */
    private class FakeWorkletProcess : WorkletProcess {
        private val exit = CompletableDeferred<Int>()
        override val stdin: OutputStream = ByteArrayOutputStream()
        override val stdout: InputStream = ByteArrayInputStream(ByteArray(0))
        override val isAlive: Boolean get() = !exit.isCompleted
        override suspend fun awaitExit(): Int = exit.await()
        override fun destroy() { exit.complete(143) }
        fun exitNow(code: Int = 1) { exit.complete(code) }
    }

    private class FakeProcessSpawner(
        private val onSpawn: (FakeWorkletProcess) -> Unit = {},
    ) : ProcessSpawner {
        private val processes = CopyOnWriteArrayList<FakeWorkletProcess>()
        val spawned: List<FakeWorkletProcess> get() = processes
        val spawnCount: Int get() = processes.size

        override fun spawn(command: List<String>): WorkletProcess {
            val p = FakeWorkletProcess()
            processes += p
            onSpawn(p)
            return p
        }
    }

    private fun cfg(
        enabled: Boolean,
        maxRestartAttempts: Int = 5,
        backoffBaseMs: Long = 10,
    ) = AppConfig.Worklet(
        enabled = enabled,
        command = listOf("worklet"),
        pingTimeoutMs = 100,
        maxRestartAttempts = maxRestartAttempts,
        backoffBaseMs = backoffBaseMs,
    )

    private suspend fun awaitUntil(timeoutMs: Long = 3000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            delay(5)
        }
    }

    @Test
    fun disabledNeverSpawnsAndPingIsFalse() = runBlocking {
        val spawner = FakeProcessSpawner()
        val supervisor = WorkletSupervisor(cfg(enabled = false), spawner)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val job = supervisor.start(scope)
            assertNull(job)                         // disabled => no supervision job
            assertEquals(0, spawner.spawnCount)     // nothing spawned
            assertFalse(supervisor.ping())          // no live RPC => false
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun respawnsWhenProcessExits() = runBlocking {
        val spawner = FakeProcessSpawner() // processes do NOT auto-exit
        val supervisor = WorkletSupervisor(cfg(enabled = true, backoffBaseMs = 1), spawner, sleeper = {})
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            supervisor.start(scope)
            awaitUntil { spawner.spawnCount == 1 }
            spawner.spawned[0].exitNow(1)           // kill the first process
            awaitUntil { spawner.spawnCount >= 2 }  // supervisor must bring up a replacement
            assertTrue(spawner.spawnCount >= 2)
        } finally {
            supervisor.close()
            scope.cancel()
        }
    }

    @Test
    fun backoffIsExponentialAndRestartsAreCapped() = runBlocking {
        val backoffs = CopyOnWriteArrayList<Long>()
        // Every spawned process dies immediately, so the loop exhausts its restart budget fast.
        val spawner = FakeProcessSpawner(onSpawn = { it.exitNow(1) })
        val supervisor = WorkletSupervisor(
            cfg(enabled = true, maxRestartAttempts = 3, backoffBaseMs = 10),
            spawner,
            sleeper = { backoffs += it },           // record instead of waiting
        )
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val job = supervisor.start(scope)!!
            job.join()                              // loop runs to completion (budget spent)

            assertEquals(4, spawner.spawnCount)                 // initial + 3 restarts, then give up
            assertEquals(listOf(10L, 20L, 40L), backoffs.toList()) // capped exponential: base * 2^(n-1)
        } finally {
            scope.cancel()
        }
    }
}
