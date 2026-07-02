// #121 inc-2 (P2P-ADR 0003): the Hyper* core — Hypercore append/get + Hyperswarm join/replicate,
// exposed over the same seam ping.mjs uses. This increment proves the log + replication data path
// ONLY; Hyperbee/Hyperdrive/Autobase (structured stores on top of a core) are later increments.
//
// A Hypercore is an append-only, signed, replicable log. The writer holds the secret key and calls
// append(); readers open the same public key and pull blocks over a replication stream (a direct
// duplex, or a Hyperswarm connection). `core.get(seq)` on a reader AWAITS the block, so it doubles
// as "wait until replicated" — the Kotlin caller bounds that wait with the RPC timeout.
//
// ponytail: cores are keyed by a small integer handle in an in-process Map. Storage is a fresh
// per-core temp dir (Hypercore 11 dropped random-access-memory; its rocksdb backend needs a path,
// and rocksdb-native runs under bare). Durable/on-disk storage config, GC, and reopen are out of
// scope for a slice whose only job is "append replicates".
import os from 'bare-os'
import path from 'bare-path'
import crypto from 'hypercore-crypto'
import Hypercore from 'hypercore'
import Hyperswarm from 'hyperswarm'

// handle -> { core, swarm }. swarm is lazily created on the first swarmJoin for that core.
const cores = new Map()
let nextHandle = 1

const tmpStorage = () =>
  path.join(os.tmpdir(), 'viewrr-core-' + crypto.randomBytes(8).toString('hex'))

function entryOf(handle) {
  const entry = cores.get(handle)
  if (!entry) throw new Error(`unknown core handle ${handle}`)
  return entry
}

/**
 * openCore({ keyHex?, storageDir? }) -> { handle, keyHex, writable, length }
 * No keyHex → a fresh writable core (we own a new keypair); its returned keyHex is what a reader
 * opens. With keyHex → a read-only replica of that core (no data until it replicates).
 */
export async function openCore(params = {}) {
  const storageDir = params.storageDir || tmpStorage()
  const key = params.keyHex ? Buffer.from(params.keyHex, 'hex') : undefined
  const core = key ? new Hypercore(storageDir, key) : new Hypercore(storageDir)
  await core.ready()
  const handle = nextHandle++
  cores.set(handle, { core, swarm: null })
  return {
    handle,
    keyHex: core.key.toString('hex'),
    writable: core.writable,
    length: core.length,
  }
}

/** append({ handle, data }) -> { length }. data is utf-8 text; one string = one block. */
export async function append(params) {
  const { core } = entryOf(params.handle)
  await core.append(Buffer.from(String(params.data), 'utf-8'))
  return { length: core.length }
}

/**
 * get({ handle, seq }) -> { seq, data }. Resolves the block at seq as utf-8. On a reader this AWAITS
 * replication of that block, so the caller's RPC timeout is what bounds "peer never showed up".
 */
export async function get(params) {
  const { core } = entryOf(params.handle)
  const block = await core.get(params.seq)
  return { seq: params.seq, data: block == null ? null : Buffer.from(block).toString('utf-8') }
}

/** length({ handle }) -> { length }. Local block count (a reader's grows as it replicates). */
export function length(params) {
  return { length: entryOf(params.handle).core.length }
}

/**
 * swarmJoin({ handle, topicHex }) -> { topicHex, server, client }. Joins the 32-byte topic on a
 * lazily-created Hyperswarm and replicates this core with every peer discovered on it. A writable
 * core joins as server (announces "I serve this"); a read-only replica joins as client (looks peers
 * up). Both replicate on connection — that's the live analogue of replicateDirect below.
 */
export async function swarmJoin(params) {
  const entry = entryOf(params.handle)
  if (!entry.swarm) {
    entry.swarm = new Hyperswarm()
    entry.swarm.on('connection', (conn) => entry.core.replicate(conn))
  }
  const topic = Buffer.from(params.topicHex, 'hex')
  const server = entry.core.writable
  const discovery = entry.swarm.join(topic, { server, client: !server })
  await discovery.flushed() // resolves once the topic is announced/looked-up on the DHT
  return { topicHex: params.topicHex, server, client: !server }
}

/**
 * replicateDirect(handleA, handleB): wire two LOCAL cores together over an in-memory duplex — no
 * DHT, no network. This is the deterministic proof primitive: pipe A's replication stream into B's
 * and back, and B pulls A's blocks. Not on the RPC surface (hyperlet.mjs never maps it); it exists
 * for hyper-selftest.mjs, which is why the core data path can be asserted without swarm flakiness.
 */
export function replicateDirect(handleA, handleB) {
  const a = entryOf(handleA).core
  const b = entryOf(handleB).core
  const streamA = a.replicate(true) // initiator
  const streamB = b.replicate(false)
  streamA.pipe(streamB).pipe(streamA)
}
