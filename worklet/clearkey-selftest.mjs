// #122 (P2P-ADR 0001): the DETERMINISTIC self-custody clear-key proof (primary, no network).
//
// Slices 5a/5b landed the pieces — identity keypair (identity.mjs), pubkey-sealed content key
// (seal.mjs), in-worklet segment decrypt (clearkey.mjs) — and ping.mjs exposes them over the seam
// (loadIdentity -> openContentKey -> decryptSegment). But until now nothing exercised the three
// TOGETHER against the frozen GOLDEN, and the headline #122 invariant ("the content key never
// crosses the RPC seam") had no runtime assertion. This self-test wires the whole self-custody path
// in ONE process and proves it, purely in-memory (libsodium sealed box + XChaCha20-Poly1305, zero
// swarm/network). Exit 0 = proven, non-zero = broken; a self-timer guards any pathological hang.
// Mirror of topic-selftest.mjs / hyper-selftest.mjs for the content-protection path.
//
// server/.../worklet/WorkletClearKeySmokeTest.kt runs it as the PRIMARY, CI-safe assertion, gated on
// bare + worklet/node_modules exactly like its sibling smoke tests.
import crypto from 'hypercore-crypto'
import { sealKeyTo, openSealedKey } from './seal.mjs'
import { decryptSegment, KEYBYTES } from './clearkey.mjs'
import { exit } from './stdio.mjs'

const FAIL = (msg) => {
  console.log('[clearkey-selftest] FAIL: ' + msg)
  exit(1)
}

const timer = setTimeout(() => FAIL('timed out (5s)'), 5_000)

// FROZEN cross-repo GOLDEN (pinned in clearkey.mjs; ingest encryptor + #142 mobile + viewrr-web
// reproduce it byte-for-byte). Decrypting it recovers the plaintext under the golden content key.
const CONTENT_KEY = Buffer.alloc(KEYBYTES, 0x07)          // key = 0x07 * 32
const BASE_NONCE = Buffer.alloc(16, 0x00)                 // baseNonce = 0x00 * 16
const GOLDEN_SEG = 0
const GOLDEN_PLAINTEXT = 'segment-0-plaintext'
const GOLDEN_CIPHERTEXT_HEX =
  '430589af13434a1f3d4bd7497f4c833429a2b04431762d8b3ba50c1dc7d9a874c76ff4'

try {
  // The owner/ingest side derives the recipient identity exactly as the worklet does (slice 2):
  // hypercore-crypto keyPair from a 32-byte seed. A SECOND, unrelated identity stands in for an
  // outsider who must NOT be able to open the sealed key.
  const owner = crypto.keyPair(Buffer.alloc(32, 0x07))
  const outsider = crypto.keyPair(Buffer.alloc(32, 0x42))

  // 1. Seal the content key to the owner's identity pubkey (owner/ingest side). The sealed blob is
  //    non-deterministic (ephemeral sender key), so we assert its shape, not a fixed value.
  const sealedHex = sealKeyTo(CONTENT_KEY, owner.publicKey)
  if (!/^[0-9a-f]+$/.test(sealedHex)) FAIL(`sealed blob is not lowercase hex: ${sealedHex}`)
  if (Buffer.from(sealedHex, 'hex').length !== KEYBYTES + 48) {
    FAIL(`sealed blob is ${Buffer.from(sealedHex, 'hex').length} bytes, expected ${KEYBYTES + 48}`)
  }
  // The raw key must not appear in the sealed blob — the whole point of sealing.
  if (sealedHex.includes(CONTENT_KEY.toString('hex'))) FAIL('raw content key leaked into the sealed blob')

  // 2. Open it INSIDE the worklet with the owner's identity secret key (slice 5b). open(seal(k)) === k
  //    even though the blob is non-deterministic. This is the key that never crosses the seam.
  const opened = openSealedKey(sealedHex, owner.publicKey, owner.secretKey)
  if (!opened.equals(CONTENT_KEY)) FAIL(`opened key != content key: ${opened.toString('hex')}`)

  // 3. Self-custody end-to-end: decrypt the GOLDEN segment with the SEALED-then-OPENED key (slice 5a).
  //    Proves the sealed delivery path yields a key that decrypts real ciphertext to the frozen golden.
  const plain = decryptSegment(opened, BASE_NONCE, GOLDEN_SEG, Buffer.from(GOLDEN_CIPHERTEXT_HEX, 'hex'))
  if (plain.toString('utf-8') !== GOLDEN_PLAINTEXT) {
    FAIL(`decrypted "${plain.toString('utf-8')}", expected "${GOLDEN_PLAINTEXT}"`)
  }

  // 4. Only the owner opens it: an outsider's secret key must FAIL to open the sealed blob (anonymous
  //    sealed box — recipient-only). This is the confidentiality guarantee of pubkey sealing.
  let outsiderOpened = false
  try {
    openSealedKey(sealedHex, outsider.publicKey, outsider.secretKey)
    outsiderOpened = true
  } catch (_) { /* expected */ }
  if (outsiderOpened) FAIL('an outsider secret key opened the sealed content key — sealing is broken')

  // 5. Authentication: a tampered segment must THROW, not silently return garbage (XChaCha20-Poly1305
  //    is authenticated — a corrupted or wrong-key ciphertext is rejected).
  const tampered = Buffer.from(GOLDEN_CIPHERTEXT_HEX, 'hex')
  tampered[0] ^= 0x01
  let tamperAccepted = false
  try {
    decryptSegment(opened, BASE_NONCE, GOLDEN_SEG, tampered)
    tamperAccepted = true
  } catch (_) { /* expected */ }
  if (tamperAccepted) FAIL('a tampered segment decrypted without an auth failure — authentication is broken')

  clearTimeout(timer)
  // GOLDEN line pins the frozen cross-repo vector (ingest + #142 mobile + viewrr-web reproduce it).
  console.log(`[clearkey-selftest] GOLDEN key=0x07*32 seg=${GOLDEN_SEG} -> "${GOLDEN_PLAINTEXT}"`)
  console.log('[clearkey-selftest] SELF_CUSTODY_OK: seal->open(secretKey)->decrypt; outsider-open & tamper rejected')
  exit(0)
} catch (e) {
  clearTimeout(timer)
  FAIL(String(e?.stack ?? e))
}
