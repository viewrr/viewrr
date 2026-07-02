// #121 slice 5a / #122 (P2P-ADR 0001): self-custody clear-key segment decryption, in-worklet.
//
// Media is stored encrypted; the worklet holds the content key (opened from a pubkey-sealed blob —
// increment 5b) and decrypts each segment IN MEMORY. The key never leaves the worklet; only
// plaintext segment bytes cross the RPC seam to the player. A compromised Hub/JVM can observe what
// the user is actively watching but can never extract the key.
//
// FROZEN cross-repo contract (ingest encryptor + #142 mobile + viewrr-web decryptors reproduce
// byte-for-byte):
//   cipher = XChaCha20-Poly1305-IETF (sodium-universal; authenticated — NOT #122's sketched
//            AES-128, which is unauthenticated and needs a separate MAC). 32-byte key, 16-byte tag.
//   nonce(segIndex) = 24 bytes: baseNonce[0..16) ‖ u32LE(segIndex) ‖ 0x00*4
//   GOLDEN: key=0x07*32, baseNonce=0x00*16, seg 0, plaintext "segment-0-plaintext"
//        -> ciphertext 430589af13434a1f3d4bd7497f4c833429a2b04431762d8b3ba50c1dc7d9a874c76ff4
import sodium from 'sodium-universal'

const AEAD = 'crypto_aead_xchacha20poly1305_ietf'
export const KEYBYTES = sodium[`${AEAD}_KEYBYTES`]   // 32
export const NPUBBYTES = sodium[`${AEAD}_NPUBBYTES`] // 24
export const ABYTES = sodium[`${AEAD}_ABYTES`]       // 16

/** Deterministic per-segment nonce. baseNonce is 16 bytes; segIndex a u32. */
export function deriveNonce(baseNonce, segIndex) {
  if (baseNonce.length !== 16) throw new Error(`baseNonce must be 16 bytes, got ${baseNonce.length}`)
  const n = Buffer.alloc(NPUBBYTES)
  baseNonce.copy(n, 0)
  n.writeUInt32LE(segIndex >>> 0, 16)
  return n
}

/** Decrypt one segment. Throws on auth failure (tampered/ wrong key). Returns plaintext Buffer. */
export function decryptSegment(key, baseNonce, segIndex, ciphertext) {
  if (key.length !== KEYBYTES) throw new Error(`key must be ${KEYBYTES} bytes, got ${key.length}`)
  if (ciphertext.length < ABYTES) throw new Error('ciphertext shorter than auth tag')
  const out = Buffer.alloc(ciphertext.length - ABYTES)
  sodium[`${AEAD}_decrypt`](out, null, ciphertext, null, deriveNonce(baseNonce, segIndex), key)
  return out
}
