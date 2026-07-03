// #126 (P2P-ADR 0006): the Vault Link QR wire-format proof (CI-safe, no network).
//
// The QR is the SOLE rendezvous for a new device, so its encoding must be a frozen, reproducible,
// integrity-checked contract: the trusted device encodes a fresh pairing secret and the new device
// decodes the SAME secret (and lands on the SAME topic) with no coordination, while a corrupt or
// wrong-version scan is REJECTED locally instead of silently joining a garbage topic. This asserts
// exactly that, purely in-process (base64url + keyed BLAKE2b, zero swarm/network), so it is the
// CI-safe assertion the Kotlin WorkletPairingSmokeTest gates on bare+deps alone. Exit 0 = proven,
// non-zero = the format drifted/broke. Mirror of topic-selftest.mjs for the QR-carriage path.
import { encodeVaultLink, decodeVaultLink } from './vaultlink-qr.mjs'
import { pairingTopic } from './topic.mjs'
import { exit } from './stdio.mjs'

const FAIL = (msg) => {
  console.log('[vaultlink-selftest] FAIL: ' + msg)
  exit(1)
}

const timer = setTimeout(() => FAIL('timed out (5s)'), 5_000)

try {
  const secretHex = '11'.repeat(32) // 32-byte one-time pairing secret

  // 1. Round-trip: encode -> decode recovers the SAME secret, and the SAME topic pairing.mjs joins.
  const uri = encodeVaultLink(secretHex)
  const dec = decodeVaultLink(uri)
  if (dec.pairingSecretHex !== secretHex) FAIL(`round-trip lost the secret: ${dec.pairingSecretHex}`)
  const expectedTopic = pairingTopic(Buffer.from(secretHex, 'hex'))
  if (dec.topicHex !== expectedTopic) FAIL(`decoded topic ${dec.topicHex} != pairingTopic ${expectedTopic}`)

  // 2. FROZEN GOLDEN: the exact URI for 0x11*32 is pinned so the cross-repo wire format cannot drift
  //    silently (#142 mobile + viewrr-web must reproduce this byte-for-byte).
  const GOLDEN = 'viewrr-vaultlink:v1:EREREREREREREREREREREREREREREREREREREREREREG8kHA'
  if (uri !== GOLDEN) FAIL(`golden URI drift:\n  got  ${uri}\n  want ${GOLDEN}`)

  // 3. Corruption is REJECTED: flip the last body char -> checksum must catch it (no silent bad topic).
  const flipped = uri.slice(0, -1) + (uri.slice(-1) === 'A' ? 'B' : 'A')
  let rejected = false
  try { decodeVaultLink(flipped) } catch (_) { rejected = true }
  if (!rejected) FAIL(`a corrupted vault-link was accepted: ${flipped}`)

  // 4. Wrong version is REJECTED (forward-compat guard: a v2 QR must not be silently parsed as v1).
  let versionRejected = false
  try { decodeVaultLink(uri.replace(':v1:', ':v2:')) } catch (_) { versionRejected = true }
  if (!versionRejected) FAIL('a non-v1 vault-link URI was accepted as v1')

  clearTimeout(timer)
  console.log(`[vaultlink-selftest] GOLDEN secret=0x11*32 -> ${GOLDEN}`)
  console.log('[vaultlink-selftest] ROUNDTRIP_OK: encode->decode preserves secret+topic; corrupt & non-v1 rejected')
  exit(0)
} catch (e) {
  clearTimeout(timer)
  FAIL(String(e?.stack ?? e))
}
