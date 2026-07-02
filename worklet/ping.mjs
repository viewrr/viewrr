// #121 slice 1 (P2P-ADR 0003): smoke worklet — newline-delimited JSON-RPC over stdio.
//
// Runtime-agnostic ON PURPOSE: plain process.stdin/process.stdout, no imports, so the SAME source
// runs unchanged under both `bare` (desktop/server subprocess, mobile bare-kit in-process) and
// `node`. Reads `{"id":N,"method":"ping"}` lines on stdin and replies `{"id":N,"result":"pong"}`.
// Unknown methods are ignored (no reply), matching WorkletRpc's ignore-unknown-id path.
//
// This is the entire worklet for slice 1 — no P2P/Hyper*/swarm logic. Later slices add real methods.

let buffer = ''

process.stdin.on('data', (chunk) => {
  buffer += chunk // Buffer or string; += coerces to utf8 text, fine for ASCII JSON on bare and node
  let index
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index)
    buffer = buffer.slice(index + 1)
    if (line.trim().length === 0) continue

    let msg
    try {
      msg = JSON.parse(line)
    } catch (_) {
      continue // drop garbage lines rather than crash the worklet
    }

    if (msg && msg.id !== undefined && msg.method === 'ping') {
      process.stdout.write(JSON.stringify({ id: msg.id, result: 'pong' }) + '\n')
    }
    // Any other method is intentionally ignored — no reply, no error.
  }
})
