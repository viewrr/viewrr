// #121 worklet entry — newline-delimited JSON-RPC over stdio, running on Bare (its intended runtime).
//
// Methods:
//   ping                       -> "pong"                                (slice 1, health)
//   identity {seed}            -> { publicKey, signature } hex           (slice 2, #121)
//   announce {contentUuids}    -> { joined: <count> }                    (slice 3, #121)
//   swarmStatus                -> { topics:[hex], peers:<n> }            (slice 3, debug)
//   lookup {contentUuid,timeoutMs?} -> { peers:[hex] }                   (slice 4, #121)
//
// Reads `{"id":N,"method":..,"params":..}` lines on stdin, replies `{"id":N,"result":..}` or
// `{"id":N,"error":..}`. Unknown methods are ignored (no reply), matching WorkletRpc's
// ignore-unknown-id path. Slices 2-3 pull in hypercore-crypto + hyperswarm, so this is no longer
// import-free — it needs `npm i` in worklet/ and a bare/node runtime with the deps.
import { deriveIdentity } from './identity.mjs'
import { swarmTopic } from './topic.mjs'
import { openStdio } from './stdio.mjs'
import { decryptSegment } from './clearkey.mjs'
import Hyperswarm from 'hyperswarm'

// #121 slice 5a / #122: the content key + base nonce live ONLY here, set via setContentKey and
// never emitted back over the seam. decryptSegment returns plaintext bytes; the key does not.
// ponytail: setContentKey takes the raw key for now (bootstrap); opening a pubkey-sealed blob with
// the worklet secretKey — so the key never travels in the clear even to here — is increment 5b.
let contentKey = null
let baseNonce = null

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

// #121 slice 4: lookup-only. Join the content's topic as a client, wait briefly, return whoever
// connected. ponytail: peers are just the connections open within the window — a full DHT lookup is
// best-effort; slice 5 does the actual byte transfer over these connections.
async function lookup(contentUuid, timeoutMs = 2000) {
  if (typeof contentUuid !== 'string') throw new Error('contentUuid must be a string')
  if (swarm === null) swarm = new Hyperswarm()
  const topic = swarmTopic(contentUuid)
  if (!joinedTopics.has(topic)) {
    swarm.join(Buffer.from(topic, 'hex'), { server: false, client: true })
    joinedTopics.add(topic)
  }
  await new Promise((resolve) => setTimeout(resolve, timeoutMs))
  const peers = [...swarm.connections].map((c) => c.remotePublicKey.toString('hex'))
  return { peers }
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
    } else if (msg.method === 'lookup') {
      lookup(msg.params?.contentUuid, msg.params?.timeoutMs)
        .then((result) => stdout.write(JSON.stringify({ id: msg.id, result }) + '\n'))
        .catch((e) => stdout.write(JSON.stringify({ id: msg.id, error: String(e?.message ?? e) }) + '\n'))
    } else if (msg.method === 'setContentKey') {
      try {
        contentKey = Buffer.from(msg.params?.keyHex ?? '', 'hex')
        baseNonce = Buffer.from(msg.params?.baseNonceHex ?? '', 'hex')
        if (contentKey.length !== 32 || baseNonce.length !== 16) throw new Error('bad key/nonce length')
        stdout.write(JSON.stringify({ id: msg.id, result: { ok: true } }) + '\n') // key is NOT echoed
      } catch (e) {
        contentKey = null; baseNonce = null
        stdout.write(JSON.stringify({ id: msg.id, error: String(e?.message ?? e) }) + '\n')
      }
    } else if (msg.method === 'decryptSegment') {
      try {
        if (contentKey === null) throw new Error('no content key set')
        const plain = decryptSegment(contentKey, baseNonce, msg.params?.segIndex >>> 0,
          Buffer.from(msg.params?.cipherHex ?? '', 'hex'))
        stdout.write(JSON.stringify({ id: msg.id, result: { plaintextHex: plain.toString('hex') } }) + '\n')
      } catch (e) {
        stdout.write(JSON.stringify({ id: msg.id, error: String(e?.message ?? e) }) + '\n')
      }
    }
    // Any other method is intentionally ignored — no reply, no error.
  }
})
