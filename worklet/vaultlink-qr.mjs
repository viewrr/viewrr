// #126 (P2P-ADR 0006): the Vault Link QR WIRE FORMAT — how the ephemeral pairing secret is carried.
//
// ADR 0006 decision #2 says "The QR carries a fresh pairing secret". pairing.mjs mints that secret
// (pairBegin), but a raw 64-char hex string is NOT a QR contract: nothing pins a scheme, a version,
// or an integrity check, so a mistyped / partially-scanned / wrong-app QR would be fed straight into
// pairJoin and the new device would silently join a GARBAGE topic and hang on a peer that never comes.
// This module is that missing contract: a versioned, self-describing, integrity-checked URI that the
// trusted device renders as a QR and the new device scans.
//
//   encodeVaultLink(pairingSecretHex) -> "viewrr-vaultlink:v1:<base64url(secret ‖ checksum)>"
//   decodeVaultLink(uri)              -> { pairingSecretHex, topicHex }   (checksum-verified)
//
// The checksum is a 4-byte prefix of keyed BLAKE2b(key = secret, msg = domain) — CORRUPTION detection
// only, NOT a security boundary: the pairing secret itself is the entropy (see pairingTopic), and the
// topic already dies with the swarm. It exists so a corrupt scan is REJECTED locally, before any DHT
// join, with a clear error instead of a silent no-peer timeout. decodeVaultLink also returns topicHex
// (= pairingTopic(secret)) so the scanner has the join target in one step, exactly like pairBegin.
//
// FROZEN cross-repo contract (#142 mobile + viewrr-web reproduce this byte-for-byte):
//   scheme    "viewrr-vaultlink"          (URI scheme, lowercase)
//   version   "v1"
//   body      base64url( secret[32] ‖ checksum[4] ), no '=' padding
//   checksum  BLAKE2b-256(key = secret, message = "viewrr-vaultlink:v1:checksum")[0..4)
//   GOLDEN    pairingSecret = 0x11*32
//             -> "viewrr-vaultlink:v1:EREREREREREREREREREREREREREREREREREREREREREG8kHA"  (see selftest)
//
// Additive to the #158 pairing surface: pure functions, no swarm, no new deps (sodium-universal +
// the existing pairingTopic). Touches only pairing/topic files, per the #126 coordination boundary.
import sodium from 'sodium-universal'
import { pairingTopic } from './topic.mjs'

const SCHEME = 'viewrr-vaultlink'
const VERSION = 'v1'
const PREFIX = `${SCHEME}:${VERSION}:`
const SECRET_BYTES = 32
const CHECKSUM_BYTES = 4
// Domain-separated checksum message so this keyed hash can never collide with pairingTopic/privateTopic.
const CHECKSUM_DOMAIN = Buffer.from(`${SCHEME}:${VERSION}:checksum`, 'utf-8')

/** 4-byte corruption checksum: prefix of keyed BLAKE2b(key = secret, msg = fixed domain). */
function checksum(secret) {
  const out = Buffer.alloc(sodium.crypto_generichash_BYTES)
  sodium.crypto_generichash(out, CHECKSUM_DOMAIN, secret)
  return out.subarray(0, CHECKSUM_BYTES)
}

/** Buffer -> base64url (RFC 4648 §5), no padding — the QR-/URI-safe alphabet. */
function base64urlEncode(buf) {
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** base64url (padded or not) -> Buffer. Tolerant of standard base64 too, so a lenient scanner still works. */
function base64urlDecode(str) {
  const norm = String(str).replace(/-/g, '+').replace(/_/g, '/')
  const pad = norm.length % 4 === 0 ? '' : '='.repeat(4 - (norm.length % 4))
  return Buffer.from(norm + pad, 'base64')
}

/**
 * encodeVaultLink(pairingSecretHex) -> vault-link URI. Wraps the 32-byte one-time pairing secret
 * (from pairBegin) in the frozen, integrity-checked, versioned envelope that becomes the QR.
 */
export function encodeVaultLink(pairingSecretHex) {
  const secret = Buffer.from(String(pairingSecretHex), 'hex')
  if (secret.length !== SECRET_BYTES) {
    throw new Error(`pairing secret must be ${SECRET_BYTES} bytes, got ${secret.length}`)
  }
  const body = Buffer.concat([secret, checksum(secret)])
  return PREFIX + base64urlEncode(body)
}

/**
 * decodeVaultLink(uri) -> { pairingSecretHex, topicHex }. Parses a scanned vault-link URI, REJECTING
 * (throwing) on wrong scheme/version, wrong length, or checksum mismatch — so a corrupt QR fails here,
 * locally, instead of downstream as a silent DHT no-peer timeout. topicHex = pairingTopic(secret).
 */
export function decodeVaultLink(uri) {
  const s = String(uri).trim()
  if (!s.startsWith(PREFIX)) {
    throw new Error(`not a ${SCHEME} ${VERSION} URI (expected prefix "${PREFIX}")`)
  }
  const body = base64urlDecode(s.slice(PREFIX.length))
  if (body.length !== SECRET_BYTES + CHECKSUM_BYTES) {
    throw new Error(`vault-link body must be ${SECRET_BYTES + CHECKSUM_BYTES} bytes, got ${body.length}`)
  }
  const secret = body.subarray(0, SECRET_BYTES)
  const crc = body.subarray(SECRET_BYTES)
  if (!crc.equals(checksum(secret))) {
    throw new Error('vault-link checksum mismatch (corrupt or mistyped QR)')
  }
  return { pairingSecretHex: secret.toString('hex'), topicHex: pairingTopic(secret) }
}
