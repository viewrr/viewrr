// #121 slice 3 (P2P-ADR 0008): swarm topic derivation for content-addressed availability.
//
// swarmTopic(contentUuid) = hypercore-crypto.hash(uuidBytes) → 32-byte Hyperswarm topic. A node
// holding a copy joins hash(content_uuid) as a provider; a peer wanting it joins the same topic.
// Purely deterministic, no coordination — the P2P analogue of a BitTorrent infohash swarm.
//
// FROZEN cross-repo contract (#142 mobile + viewrr-web reproduce byte-for-byte):
//   uuidBytes = the 16 raw bytes of the content_uuid (dashes stripped, hex-decoded)
//   topic     = hypercore-crypto hash(uuidBytes)   (32 bytes, lowercase hex)
// GOLDEN: content_uuid "bc592db3-805a-58ff-9f95-b90687681997"
//      -> topic        "a4f704e6350a26c2910080d281a6097163a86aa7f19f4921fc10f7bac0df1643"
import crypto from 'hypercore-crypto'
import sodium from 'sodium-universal'

/** contentUuidHex: a content_uuid with or without dashes. Returns the 32-byte topic as hex. */
export function swarmTopic(contentUuidHex) {
  const hex = contentUuidHex.replace(/-/g, '').toLowerCase()
  const bytes = Buffer.from(hex, 'hex')
  if (bytes.length !== 16) throw new Error(`content_uuid must be 16 bytes, got ${bytes.length}`)
  return crypto.hash(bytes).toString('hex')
}

// #126 (P2P-ADR 0006): PRIVATE discovery topics derived from a DEVICE-SHARED SECRET.
//
// swarmTopic() above is a PUBLIC infohash — anyone holding the content_uuid computes it. That's
// correct for content availability, wrong for private device sync: the whole point of #126 is a
// topic OUTSIDERS CANNOT COMPUTE OR JOIN. So the rendezvous must key off a secret only the paired
// devices share (a vault-sync key), NEVER off the public identity key (which is, by definition,
// public — an outsider could derive the topic from it and sit on the swarm).
//
// Derivation = KEYED BLAKE2b: key = the shared secret, message = a domain-separated label. This is a
// PRF keyed by the secret — deterministic (same secret+label -> same topic) yet unforgeable without
// the key (no secret -> cannot compute the topic). It IS libsodium's own crypto_kdf primitive
// (keyed BLAKE2b); we call crypto_generichash directly because sodium-universal 5.0.1 exposes
// neither crypto_kdf_hkdf_sha256 nor a variable-context crypto_kdf, and crypto_generichash gives us
// exactly "key ⊕ arbitrary-length label -> 32-byte topic" with zero new deps.
//
// FROZEN cross-repo contract (#142 mobile + viewrr-web reproduce byte-for-byte):
//   topic = BLAKE2b-256( key = secret, message = "viewrr:private-topic:v1:" ‖ label )
//   GOLDEN: secret 0x42*32, label "vault-sync"
//        -> topic  see topic-selftest.mjs (printed) / WorkletPairingSmokeTest golden.
const PRIVATE_TOPIC_PREFIX = 'viewrr:private-topic:v1:'
const KEYMIN = sodium.crypto_generichash_KEYBYTES_MIN // 16
const KEYMAX = sodium.crypto_generichash_KEYBYTES_MAX // 64

/**
 * privateTopic(secretHex, label) -> 32-byte Hyperswarm topic (lowercase hex), keyed by the shared
 * secret so only devices holding the secret can compute (and thus join) it. `label` domain-separates
 * distinct private swarms under one secret (e.g. "vault-sync" vs a future "device-presence"), and
 * guarantees these topics never collide with swarmTopic()'s public content infohashes.
 */
export function privateTopic(secretHex, label = '') {
  const secret = Buffer.from(secretHex, 'hex')
  if (secret.length < KEYMIN || secret.length > KEYMAX) {
    throw new Error(`shared secret must be ${KEYMIN}..${KEYMAX} bytes, got ${secret.length}`)
  }
  const out = Buffer.alloc(32)
  const msg = Buffer.from(PRIVATE_TOPIC_PREFIX + String(label), 'utf-8')
  sodium.crypto_generichash(out, msg, secret)
  return out.toString('hex')
}

/**
 * pairingTopic(pairingSecret) -> 32-byte topic (lowercase hex) for EPHEMERAL device pairing (Vault
 * Link). The pairing secret is a fresh one-time random value (shown as a QR); the topic is a plain
 * hash of it — no key/label, because the secret itself is the entropy and the swarm is torn down
 * after a single vault transfer. Deliberately DISTINCT from privateTopic (keyed) and swarmTopic
 * (content): the identity key is NEVER the pairing rendezvous. `pairingSecret` is a Buffer.
 */
export function pairingTopic(pairingSecret) {
  if (!Buffer.isBuffer(pairingSecret)) throw new Error('pairingSecret must be a Buffer')
  return crypto.hash(pairingSecret).toString('hex')
}
