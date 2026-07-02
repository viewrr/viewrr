// #121 slice 5b / #122 (P2P-ADR 0001): the content key is delivered SEALED to a user's identity
// public key and opened only inside the worklet with that user's secret key. The raw content key
// never travels in the clear — not to the Hub, not even into the worklet over the RPC seam.
//
// Sealing = libsodium anonymous sealed box (crypto_box_seal): an ephemeral sender keypair, so the
// sealed blob is non-deterministic and unlinkable, and only the recipient's secret key opens it.
// The recipient key is the identity Ed25519 key (slice 2), converted to its Curve25519 equivalent.
//
// FROZEN cross-repo contract (#142 mobile + viewrr-web + the ingest sealer reproduce it):
//   recipient   = crypto_sign_ed25519_pk_to_curve25519(identityPublicKey)
//   sealed(key) = crypto_box_seal(key, recipient)            // 32-byte key -> 80-byte blob (+48)
//   open(blob)  = crypto_box_seal_open(blob, curvePk, crypto_sign_ed25519_sk_to_curve25519(sk))
// Round-trip is deterministic even though the blob is not: open(seal(k)) === k (self-check below).
import sodium from 'sodium-universal'

export const SEALBYTES = sodium.crypto_box_SEALBYTES // 48

function pkToCurve(ed25519Pk) {
  const c = Buffer.alloc(sodium.crypto_box_PUBLICKEYBYTES)
  sodium.crypto_sign_ed25519_pk_to_curve25519(c, ed25519Pk)
  return c
}
function skToCurve(ed25519Sk) {
  const c = Buffer.alloc(sodium.crypto_box_SECRETKEYBYTES)
  sodium.crypto_sign_ed25519_sk_to_curve25519(c, ed25519Sk)
  return c
}

/** Owner/ingest side: seal a content key to an identity Ed25519 public key. Returns hex blob. */
export function sealKeyTo(contentKey, ed25519PublicKey) {
  const sealed = Buffer.alloc(contentKey.length + SEALBYTES)
  sodium.crypto_box_seal(sealed, contentKey, pkToCurve(ed25519PublicKey))
  return sealed.toString('hex')
}

/** Worklet side: open a sealed blob with the identity Ed25519 secret key. Throws on failure. */
export function openSealedKey(sealedHex, ed25519PublicKey, ed25519SecretKey) {
  const sealed = Buffer.from(sealedHex, 'hex')
  if (sealed.length < SEALBYTES) throw new Error('sealed blob shorter than seal overhead')
  const out = Buffer.alloc(sealed.length - SEALBYTES)
  const ok = sodium.crypto_box_seal_open(out, sealed, pkToCurve(ed25519PublicKey), skToCurve(ed25519SecretKey))
  if (!ok) throw new Error('seal open failed (wrong key or tampered)')
  return out
}
