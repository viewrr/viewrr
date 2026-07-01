# 0007 — Single crypto stack: libsodium in the Bare worklet, no JVM crypto lib

**Status:** Accepted (2026-07-01)

## Context

The desktop shell is Compose/JVM (`P2P-ADR 0002`), so a JVM crypto library such as Bouncy
Castle was proposed to "reduce code." All viewrr crypto is asymmetric + AEAD:
Ed25519 identity, NaCl `box` (X25519 + XSalsa20-Poly1305) to seal content keys,
Noise + libsodium SecretStream transport, secretbox vaults, HKDF per-segment keys.

By existing invariant, all of this runs **inside the Bare worklet** via libsodium;
"keys never cross the RPC seam" and the native shell receives only plaintext.

## Decision

**Keep libsodium in the worklet as the sole crypto stack. Do not add Bouncy Castle or
any JVM-side crypto library.**

- All key material and crypto operations stay in the worklet.
- The native shell (JVM/Swift/Kotlin) does **zero** application crypto; it receives
  plaintext over the RPC seam.
- The only platform-side key ops are OS keystore wrapping of the at-rest `secretKey`
  (Android Keystore / macOS Keychain / Secure Enclave) and biometric gating — platform
  APIs, not a crypto library.

## Consequences

- **Good:** One audited crypto implementation, one language. No byte-for-byte interop
  layer between libsodium and a JVM stack, which is exactly where silent crypto
  mismatches hide (BC has no clean NaCl `box`/XSalsa20 anyway).
- **Good:** Genuinely less code — adding BC would have added a second stack, not
  removed one.
- **Constraint:** Any future JVM-side need for symmetric ops (e.g. the desktop
  segment-decrypt shim) is still satisfied inside the worklet (worklet decrypts, feeds
  the player), preserving this invariant.
