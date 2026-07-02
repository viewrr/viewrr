// #121 slice 2 (P2P-ADR 0003): self-custody identity derivation for the worklet.
//
// Derives an Ed25519 keypair from a 32-byte seed via hypercore-crypto (libsodium ed25519 — the
// same stack HyperDHT keys on). The Hub's pure-JDK Ed25519Verifier (#135) accepts signatures made
// here: proven by server/src/test/kotlin/worklet/WorkletIdentityParityTest.kt. So ONE mnemonic
// yields ONE identity usable in both app auth and the P2P swarm.
//
// FROZEN cross-repo contract (#142 mobile + viewrr-web must reproduce byte-for-byte):
//   - keyPair seed = 32 bytes. Upstream, clients derive it as the FIRST 32 BYTES of the
//     BIP39-512 seed (mnemonic -> bip39 seed64 -> seed64[0:32]).
//   - keypair = hypercore-crypto keyPair(seed)  (== libsodium crypto_sign_seed_keypair)
//   - REGISTER_MESSAGE = the exact bytes "viewrr:register" (server IdentityService.REGISTER_MESSAGE)
//   - publicKey / signature are lowercase hex.
import crypto from 'hypercore-crypto'

export const REGISTER_MESSAGE = 'viewrr:register'

/** seedHex: 32-byte (64 hex char) keyPair seed. Returns { publicKey, signature } as lowercase hex. */
export function deriveIdentity(seedHex) {
  const seed = Buffer.from(seedHex, 'hex')
  if (seed.length !== 32) throw new Error(`seed must be 32 bytes, got ${seed.length}`)
  const kp = crypto.keyPair(seed)
  const signature = crypto.sign(Buffer.from(REGISTER_MESSAGE), kp.secretKey)
  return { publicKey: kp.publicKey.toString('hex'), signature: signature.toString('hex') }
}
