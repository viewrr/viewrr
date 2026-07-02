package wtf.jobin.worklet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * #121 slice 1: a spawned worklet process, reduced to exactly what [WorkletSupervisor] needs —
 * its stdin/stdout streams (the RPC channel), a suspending way to observe exit, and a kill switch.
 * Injectable so tests drive a fake instead of a real OS process.
 */
interface WorkletProcess {
    /** Write to the subprocess's stdin (the outbound half of the control channel). */
    val stdin: OutputStream

    /** Read the subprocess's stdout (the inbound half of the control channel). */
    val stdout: InputStream

    /** True until the process has exited. */
    val isAlive: Boolean

    /** Suspends until the process exits, returning its exit code. */
    suspend fun awaitExit(): Int

    /** Requests termination. Idempotent. */
    fun destroy()
}

/** Spawns a worklet subprocess from an argv list. */
interface ProcessSpawner {
    fun spawn(command: List<String>): WorkletProcess
}

/**
 * Real [ProcessSpawner] over [ProcessBuilder]. The subprocess's stderr is inherited so worklet
 * crash output lands in the server log during bring-up; stdin/stdout stay ours for the RPC.
 */
class RealProcessSpawner : ProcessSpawner {
    override fun spawn(command: List<String>): WorkletProcess {
        require(command.isNotEmpty()) { "worklet command must not be empty" }
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        return RealWorkletProcess(process)
    }
}

private class RealWorkletProcess(private val process: Process) : WorkletProcess {
    // Java names streams from the parent's view: the child's stdout is our inputStream,
    // and we write the child's stdin via its outputStream.
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val isAlive: Boolean get() = process.isAlive

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { process.waitFor() }

    override fun destroy() {
        process.destroy()
    }
}
