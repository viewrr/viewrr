// #121 worklet entry — newline-delimited JSON-RPC over stdio (bare subprocess / node dev).
//
// Methods:
//   ping                    -> "pong"                                   (slice 1, health)
//   identity {seed}         -> { publicKey, signature } lowercase hex    (slice 2, #121)
//
// Reads `{"id":N,"method":..,"params":..}` lines on stdin, replies `{"id":N,"result":..}` or
// `{"id":N,"error":..}`. Unknown methods are ignored (no reply), matching WorkletRpc's
// ignore-unknown-id path. Slice 2 pulls in hypercore-crypto (via identity.mjs), so this is no
// longer import-free — it now requires `npm i` in worklet/ and a bare/node runtime with the dep.
import { deriveIdentity } from './identity.mjs'

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

    if (!msg || msg.id === undefined) continue
    if (msg.method === 'ping') {
      process.stdout.write(JSON.stringify({ id: msg.id, result: 'pong' }) + '\n')
    } else if (msg.method === 'identity') {
      // params.seed is the 32-byte keyPair seed (hex). ponytail: slice 2 passes seeds over the
      // seam for the parity/bootstrap path; production key custody (seed born in-worklet, never
      // crossing the seam) is a later slice per the #121 plan.
      try {
        const result = deriveIdentity(msg.params?.seed)
        process.stdout.write(JSON.stringify({ id: msg.id, result }) + '\n')
      } catch (e) {
        process.stdout.write(JSON.stringify({ id: msg.id, error: String(e?.message ?? e) }) + '\n')
      }
    }
    // Any other method is intentionally ignored — no reply, no error.
  }
})
