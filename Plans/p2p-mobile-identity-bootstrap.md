# viewrr-mobile — client identity bootstrap (P2P-ADR 0001, client side)

Status: **plan**. Scopes the work #120 assumes on mobile but that does not yet exist there —
`viewrr-mobile` is an AFinity CMP fork with **zero** identity/crypto/BIP39 code today. This is
the prerequisite for the mobile derived handle (the web reference shipped in viewrr-web#1).

## Goal

Give the mobile app a self-custody identity matching the server contract already live (#135):
a 12-word BIP39 mnemonic → seed → Ed25519 keypair, secretKey encrypted at rest by a local
master-password unlock, that authenticates to the Hub's `/identity/{register,challenge,verify}`
endpoints. No Keycloak on the client.

## The determinism contracts (get these wrong = a partitioned network)

1. **Keypair derivation.** The same mnemonic MUST yield the same Ed25519 public key in the app
   auth path AND, later, inside the #121 Bare worklet (`HyperDHT.keyPair`). Freeze ONE
   derivation (seed → `HyperDHT.keyPair` convention: blake2b of the seed → ed25519 sk/pk) and
   use it both places. Confirm the exact algorithm against the worklet before shipping increment 1
   — this is the highest-risk contract in the epic.
2. **REGISTER_MESSAGE.** Client signs the exact bytes `"viewrr:register"` (server
   `IdentityService.REGISTER_MESSAGE`). Login signs the live challenge nonce. Do not swap.
3. **Public key wire format.** Lowercase hex of the 32 raw bytes (server normalizes lowercase).
4. **deriveHandle.** Byte-for-byte the frozen contract shipped in viewrr-web#1
   (`BIP39[(pk[0]<<8|pk[1])%2048]-BIP39[(pk[2]<<8|pk[3])%2048]-hex(pk[30])hex(pk[31])`,
   golden `00*32 -> abandon-abandon-0000`). Reuse the same BIP39 English wordlist.

## Increment ladder (each = one PR, additive; does not touch existing Jellyfin/media paths)

1. **Crypto core (commonMain, pure).** BIP39 gen/validate + seed → Ed25519 keypair +
   `deriveHandle`. No storage, no UI, no platform code. Tests: BIP39 test-vector → expected
   pubkey; deriveHandle golden `abandon-abandon-0000`; sign/verify round-trip. This is the piece
   that pins every determinism contract above — land it first, alone.
2. **Secure at-rest storage (expect/actual).** Encrypt the secretKey with a key gated by the
   master password; store ciphertext only. Android → Keystore/EncryptedSharedPreferences;
   iOS → Keychain. The mnemonic seed is NEVER persisted in plaintext; master password is a local
   unlock, not the identity.
3. **Onboarding UI.** Generate → display-once → 3-word confirmation → set master password.
   (BIP39 display-once + 3-word confirm per the #120 ravencloak-bip39 draft.)
4. **Hub handshake.** `register` (sign REGISTER_MESSAGE, optional display_name from #138) then
   `challenge`→`verify` (sign nonce) → receive the app's normal session tokens. Wire to the live
   #135 endpoints. Existing Jellyfin/API auth stays until cutover.
5. **Surface it.** Show the petname + derived handle in the profile/account UI.

## Why a design pass, not an immediate build

Increment 2 forces platform secure-storage decisions (Keystore vs Keychain, key-derivation from
master password, biometric gate?) and increment 1 forces confirming the keypair derivation
against the not-yet-built worklet. Both are real decisions, not lazy one-file adds — hence plan
first, then build increment 1 (the pure, fully-testable core) once the derivation is confirmed.
