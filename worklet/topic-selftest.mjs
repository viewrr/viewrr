// #126 (P2P-ADR 0006): the DETERMINISTIC private-topic proof (primary, no network).
//
// Private discovery topics MUST be a deterministic function of the device-shared secret: two paired
// devices holding the same vault-sync key have to land on the SAME Hyperswarm topic with no round of
// coordination, while an outsider without the secret cannot compute it. This asserts exactly that,
// purely in-process (keyed BLAKE2b, zero swarm/network), so it is the CI-safe assertion the Kotlin
// WorkletPairingSmokeTest gates on bare+deps alone. Exit 0 = proven, non-zero = broken; a self-timer
// guards against any pathological hang. Mirror of hyper-selftest.mjs for the crypto path.
import { privateTopic } from './topic.mjs'
import { exit } from './stdio.mjs'

const FAIL = (msg) => {
  console.log('[topic-selftest] FAIL: ' + msg)
  exit(1)
}

const timer = setTimeout(() => FAIL('timed out (5s)'), 5_000)

try {
  const secretA = '42'.repeat(32) // 32-byte shared secret
  const secretB = '77'.repeat(32) // a DIFFERENT secret

  // 1. Deterministic: same secret + same label -> identical topic, every time.
  const t1 = privateTopic(secretA, 'vault-sync')
  const t2 = privateTopic(secretA, 'vault-sync')
  if (t1 !== t2) FAIL(`same secret+label produced different topics: ${t1} vs ${t2}`)
  if (!/^[0-9a-f]{64}$/.test(t1)) FAIL(`topic is not 32-byte lowercase hex: ${t1}`)

  // 2. Secret-bound: a different secret yields a different topic (outsider can't compute A's topic).
  const tB = privateTopic(secretB, 'vault-sync')
  if (tB === t1) FAIL(`different secrets collided on topic ${t1}`)

  // 3. Label-separated: a different label under the SAME secret yields a different topic.
  const tLabel = privateTopic(secretA, 'device-presence')
  if (tLabel === t1) FAIL(`different labels collided on topic ${t1}`)

  clearTimeout(timer)
  // GOLDEN line: pins the frozen cross-repo vector (#142 mobile + viewrr-web reproduce it).
  console.log(`[topic-selftest] GOLDEN secret=0x42*32 label="vault-sync" -> ${t1}`)
  console.log('[topic-selftest] DETERMINISTIC_OK: same->same, diff-secret->diff, diff-label->diff')
  exit(0)
} catch (e) {
  clearTimeout(timer)
  FAIL(String(e?.stack ?? e))
}
