// #121 worklet entry — newline-delimited JSON-RPC over stdio, running on Bare (its intended runtime).
//
// Methods:
//   ping                       -> "pong"                                (slice 1, health)
//   identity {seed}            -> { publicKey, signature } hex           (slice 2, #121)
//   announce {contentUuids}    -> { joined: <count> }                    (slice 3, #121)
//   swarmStatus                -> { topics:[hex], peers:<n> }            (slice 3, debug)
//
// Reads `{"id":N,"method":..,"params":..}` lines on stdin, replies `{"id":N,"result":..}` or
// `{"id":N,"error":..}`. Unknown methods are ignored (no reply), matching WorkletRpc's
// ignore-unknown-id path. Slices 2-3 pull in hypercore-crypto + hyperswarm, so this is no longer
// import-free — it needs `npm i` in worklet/ and a bare/node runtime with the deps.
import { deriveIdentity } from './identity.mjs'
import { swarmTopic } from './topic.mjs'
import { openStdio } from './stdio.mjs'
import Hyperswarm from 'hyperswarm'

// Bare-native stdio (P2P-ADR 0003): stdin/stdout are bare-pipe streams over fd 0/1, not Node's
// `process` global (which Bare, the intended runtime, does not define). See stdio.mjs. Wire
// protocol is unchanged — newline-delimited JSON frames, as WorkletRpc expects.
const { stdin, stdout } = openStdio()

// #121 slice 3: announce-only. Lazy single swarm; join each content topic as provider (server:true).
// joinedTopics makes re-announce idempotent. ponytail: join is fire-and-forget advertisement —
// serving actual bytes on a connection is slice 5, so we don't wire swarm 'connection' handlers yet.
let swarm = null
const joinedTopics = new Set()

function announce(contentUuids) {
  if (!Array.isArray(contentUuids)) throw new Error('contentUuids must be an array')
  if (swarm === null) swarm = new Hyperswarm()
  for (const uuid of contentUuids) {
    const topic = swarmTopic(uuid)
    if (joinedTopics.has(topic)) continue
    swarm.join(Buffer.from(topic, 'hex'), { server: true, client: false })
    joinedTopics.add(topic)
  }
  return { joined: joinedTopics.size }
}

let buffer = ''

stdin.on('data', (chunk) => {
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
      stdout.write(JSON.stringify({ id: msg.id, result: 'pong' }) + '\n')
    } else if (msg.method === 'identity') {
      // params.seed is the 32-byte keyPair seed (hex). ponytail: slice 2 passes seeds over the
      // seam for the parity/bootstrap path; production key custody (seed born in-worklet, never
      // crossing the seam) is a later slice per the #121 plan.
      try {
        const result = deriveIdentity(msg.params?.seed)
        stdout.write(JSON.stringify({ id: msg.id, result }) + '\n')
      } catch (e) {
        stdout.write(JSON.stringify({ id: msg.id, error: String(e?.message ?? e) }) + '\n')
      }
    } else if (msg.method === 'announce') {
      try {
        const result = announce(msg.params?.contentUuids)
        stdout.write(JSON.stringify({ id: msg.id, result }) + '\n')
      } catch (e) {
        stdout.write(JSON.stringify({ id: msg.id, error: String(e?.message ?? e) }) + '\n')
      }
    } else if (msg.method === 'swarmStatus') {
      const peers = swarm ? swarm.connections.size : 0
      stdout.write(JSON.stringify({ id: msg.id, result: { topics: [...joinedTopics], peers } }) + '\n')
    }
    // Any other method is intentionally ignored — no reply, no error.
  }
})
