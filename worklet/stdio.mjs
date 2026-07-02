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

/**
 * #121 inc-2: run the newline-delimited JSON-RPC loop `WorkletRpc` speaks, given a `handlers` map of
 * `{ method: (params) => result | Promise<result> }`. This is the SAME wire ping.mjs implements by
 * hand — extracted so the Hyper* entry (hyperlet.mjs) doesn't copy the frame parser. It adds one
 * thing ping.mjs lacked: handlers may be async (Hypercore append/get/replicate all return promises),
 * so a handler's result/throw is awaited before the `{id,result}` / `{id,error}` reply is written.
 *
 * Contract matches WorkletRpc exactly: reply on `{id,result}` success or `{id,error:<message>}`;
 * a line with no `id`, unparseable JSON, or an UNKNOWN method gets no reply (ignore-unknown-id path).
 * ponytail: opens stdio itself (the common case). Pass `io` to inject in-memory pipes from a test.
 */
export function serveRpc(handlers, io = openStdio()) {
  const { stdin, stdout } = io
  const send = (obj) => stdout.write(JSON.stringify(obj) + '\n')
  let buffer = ''
  stdin.on('data', (chunk) => {
    buffer += chunk // Buffer|string; += coerces to utf8, fine for ASCII JSON on bare and node
    let index
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index)
      buffer = buffer.slice(index + 1)
      if (line.trim().length === 0) continue
      let msg
      try {
        msg = JSON.parse(line)
      } catch (_) {
        continue // drop garbage rather than crash the worklet
      }
      if (!msg || msg.id === undefined) continue
      const handler = handlers[msg.method]
      if (!handler) continue // unknown method: no reply, no error (matches WorkletRpc)
      Promise.resolve()
        .then(() => handler(msg.params))
        .then((result) => send({ id: msg.id, result }))
        .catch((e) => send({ id: msg.id, error: String(e?.message ?? e) }))
    }
  })
  return io
}
