// #126 (P2P-ADR 0006): EPHEMERAL device pairing — "Vault Link".
//
// Bringing a NEW device into the self-custody vault can't use the identity key as the rendezvous
// (public → an outsider could join and MITM the handoff), and it can't use the steady-state private
// topic (that presumes the device already holds the shared secret — the very thing pairing delivers).
// So pairing uses a FRESH ONE-TIME secret shown as a QR on the trusted device:
//
//   1. host: pairBegin(vaultHex) → generate a random 32-byte pairingSecret; topic = hash(secret);
//            join Hyperswarm as SERVER; on the first peer connection, send `vaultHex` over the
//            (Noise-encrypted) secret-stream. Returns {pairingSecretHex, topicHex} so the QR can be
//            shown. The raw pairingSecret leaves the host ONLY as the QR, never onto the DHT.
//   2. new device: pairJoin(pairingSecretHex) → recompute the SAME topic; join as CLIENT; read the
//            vault payload off the connection; tear the pairing swarm DOWN. Returns {vaultHex}.
//   3. host: pairFinish() → wait until the vault was delivered, then tear the pairing swarm DOWN.
//
// Because Hyperswarm connections are @hyperswarm/secret-stream (Noise XX), the vault crosses an
// authenticated, encrypted channel WITHOUT us hand-rolling any crypto — the ephemeral topic is the
// only shared secret and it dies with the swarm. The vault blob itself is already the encrypted
// vault (its own key never travels in clear), so we ship it as an opaque hex payload.
//
// ponytail: ONE pairing in flight per worklet process (host XOR joiner) — a single module-level slot,
// not a handle Map. A device pairs rarely and serially; the core.mjs handle-Map machinery would be
// over-engineering here. The Kotlin WorkletPairing surface drives exactly this one-shot lifecycle.
import Hyperswarm from 'hyperswarm'
import crypto from 'hypercore-crypto'
import { pairingTopic, privateTopic } from './topic.mjs'

const PAIRING_SECRET_BYTES = 32

// The single in-flight pairing for this worklet process. { swarm, topicHex, done, delivered }.
let session = null

function assertNoSession() {
  if (session) throw new Error('a pairing is already in progress in this worklet')
}

// Frame a hex payload as one newline-terminated line (hex never contains '\n', so this is safe and
// matches the newline-delimited framing the rest of the seam already uses).
function writeFramed(conn, hex) {
  conn.write(Buffer.from(hex + '\n', 'utf-8'))
}

// Read exactly one newline-terminated frame off a secret-stream, resolving its hex payload.
function readFramed(conn) {
  return new Promise((resolve, reject) => {
    let buffer = ''
    const onData = (chunk) => {
      buffer += chunk.toString('utf-8')
      const nl = buffer.indexOf('\n')
      if (nl < 0) return
      conn.removeListener('data', onData)
      conn.removeListener('error', onError)
      resolve(buffer.slice(0, nl))
    }
    const onError = (e) => {
      conn.removeListener('data', onData)
      reject(e)
    }
    conn.on('data', onData)
    conn.on('error', onError)
  })
}

async function teardown() {
  if (!session) return
  const swarm = session.swarm
  session = null
  if (swarm) {
    await swarm.destroy().catch(() => {}) // leaves every joined topic; kills the DHT node.
  }
}

/**
 * deriveTopic({ secretHex, label }) -> { topicHex }. Steady-state PRIVATE topic (keyed BLAKE2b of the
 * device-shared secret). Deterministic; no swarm side effects — exposed here so the pairing worklet
 * can also answer the everyday "what topic does my vault sync on" question over one seam.
 */
export function deriveTopic(params) {
  return { topicHex: privateTopic(params.secretHex, params.label ?? '') }
}

/**
 * pairBegin({ vaultHex }) -> { pairingSecretHex, topicHex }. Host side. Mints a fresh pairing secret,
 * joins hash(secret) as server, and arms a one-shot handler that ships `vaultHex` to the first peer.
 * Resolves IMMEDIATELY (before any peer arrives) so the caller can render the QR; delivery completes
 * asynchronously and is awaited by pairFinish().
 */
export async function pairBegin(params) {
  assertNoSession()
  const vaultHex = String(params?.vaultHex ?? '')
  const pairingSecret = crypto.randomBytes(PAIRING_SECRET_BYTES)
  const topicHex = pairingTopic(pairingSecret)

  const swarm = new Hyperswarm()
  let resolveDone
  const done = new Promise((r) => { resolveDone = r })
  session = { swarm, topicHex, done, delivered: false }

  swarm.on('connection', (conn) => {
    conn.on('error', () => {}) // a dropped peer must not throw an uncaught error at the process
    if (session && !session.delivered) {
      session.delivered = true
      writeFramed(conn, vaultHex)
      // Flush before we let pairFinish tear down: end() half-closes after the buffered frame drains.
      conn.end()
      resolveDone()
    }
  })

  await swarm.join(Buffer.from(topicHex, 'hex'), { server: true, client: false }).flushed()
  return { pairingSecretHex: pairingSecret.toString('hex'), topicHex }
}

/**
 * pairFinish() -> { delivered }. Host side. Suspends until the vault has been handed to a peer, then
 * TEARS THE PAIRING SWARM DOWN (the ephemeral topic ceases to exist). The caller's RPC timeout is the
 * "no device ever paired" bound — a timeout there means teardown-without-delivery, which is fine.
 */
export async function pairFinish() {
  if (!session) throw new Error('no pairing in progress')
  const { done } = session
  try {
    await done
    return { delivered: true }
  } finally {
    await teardown()
  }
}

/**
 * pairJoin({ pairingSecretHex }) -> { vaultHex }. New-device side. Recomputes the topic from the QR
 * secret, joins as client, reads the vault off the Noise channel, then TEARS DOWN. A timeout on the
 * caller's side (peer never showed) leaves teardown to the caller via pairAbort(), so this resolves
 * ONLY on a real transfer.
 */
export async function pairJoin(params) {
  assertNoSession()
  const pairingSecret = Buffer.from(String(params.pairingSecretHex), 'hex')
  if (pairingSecret.length !== PAIRING_SECRET_BYTES) {
    throw new Error(`pairing secret must be ${PAIRING_SECRET_BYTES} bytes, got ${pairingSecret.length}`)
  }
  const topicHex = pairingTopic(pairingSecret)

  const swarm = new Hyperswarm()
  session = { swarm, topicHex, done: null, delivered: false }

  const vaultHex = await new Promise((resolve, reject) => {
    swarm.on('connection', (conn) => {
      conn.on('error', () => {})
      readFramed(conn).then(resolve, reject)
    })
    swarm.join(Buffer.from(topicHex, 'hex'), { server: false, client: true })
      .flushed()
      .catch(reject)
  })

  await teardown()
  return { vaultHex }
}

/** pairAbort() -> { aborted }. Force-teardown of whatever pairing this worklet holds (timeout/cancel). */
export async function pairAbort() {
  const had = session != null
  await teardown()
  return { aborted: had }
}
