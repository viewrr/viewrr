// #121 (P2P-ADR 0003): Bare-native stdio seam for the worklet.
//
// ponytail: This tiny shim is the ONLY Bare-specific code in the worklet. Under Bare there is NO
// Node `process` global (`process is not defined`) — stdin/stdout are `bare-pipe` Pipe streams over
// fd 0/1, and argv/exit live on Bare's built-in `Bare` global (so we need NO `bare-process` dep;
// `bare-pipe` is the one dep this port adds). Everything else in the worklet stays runtime-agnostic,
// and the wire protocol is unchanged: plain newline-delimited JSON frames, exactly what the Kotlin
// `WorkletRpc` seam speaks.
//
// stdio is opened LAZILY via openStdio(): constructing a Pipe on fd 0 keeps the event loop alive
// (a data-less stdin still parks the loop), so a short-lived argv-only CLI like derive.mjs must not
// pay for it — it imports `argv` only and exits cleanly.
import Pipe from 'bare-pipe'

// Bare.argv mirrors Node's process.argv: [runtimePath, scriptPath, ...userArgs]. argv[2] === first
// user arg under both runtimes, so call sites are identical.
export const argv = Bare.argv

/** Terminate the worklet. Mirrors process.exit(code). */
export const exit = (code = 0) => Bare.exit(code)

/**
 * Open the worklet control channel: stdin (fd 0, readable — `.on('data')`) and stdout (fd 1,
 * writable — `.write()`). bare-pipe's Pipe is a streamx duplex, so the readable/writable halves
 * used here match the Node stdin/stdout stream shape the worklet relied on before.
 */
export function openStdio() {
  return { stdin: new Pipe(0), stdout: new Pipe(1) }
}
