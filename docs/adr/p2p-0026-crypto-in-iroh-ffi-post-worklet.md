# 0026 — At-rest crypto moves into the Iroh Rust FFI; worklet-libsodium retired

**Status:** Accepted (2026-07-06). **Revises `p2p-0007`** (single crypto stack = libsodium
*in the Bare worklet*) — the worklet is deleted by `p2p-0019`, so the stack relocates.
Keeps `p2p-0001` (self-custody clear-key) unchanged as the *what*; this ADR is the *where*.
This is the "`p2p-0007` crypto revisit" that `p2p-0019`'s spike gate opens.

## Context

`p2p-0007` decided **all** viewrr crypto runs inside the **Bare worklet** via libsodium:
Ed25519 identity, NaCl `box` (X25519 + XSalsa20-Poly1305) to seal content keys, HKDF
per-segment AES keys, secretbox vaults. Its stated invariant: *"keys never cross the RPC
seam; the native shell (JVM/Swift) does zero application crypto and receives only
plaintext."*

`p2p-0019` (Iroh P2P core) **deletes the Bare worklet** — the JS runtime is replaced by a
native Rust library reached over an FFI. That erases the mechanism `p2p-0007` named (the
worklet) **and** the seam it named (the RPC boundary). Two crypto layers are now separable:

- **Transport crypto** — Ed25519 node keys + QUIC TLS — now comes from **Iroh itself**
  (`p2p-0019`). No longer ours.
- **At-rest / self-custody crypto** — the `p2p-0001` clear-key envelope (content key sealed
  to `publicKey`, opened with `secretKey`, per-segment HKDF AES decrypt) — **still ours, and
  now homeless.** This is what the revisit must place.

The worklet was the *mechanism*, not the *invariant*. The real `p2p-0007` invariant was:
**one crypto stack, behind one FFI seam, native shell does zero app crypto and gets
plaintext.** Iroh already reintroduces a Rust-behind-FFI seam — so the invariant can be
preserved by relocation rather than re-litigated.

## Decision

1. **At-rest crypto = one Rust module behind the Iroh FFI.** The `p2p-0001` clear-key
   envelope executes in Rust, in the **same native library and across the same FFI seam**
   that `p2p-0019` already ships for transport. Worklet → Rust-FFI relocates `p2p-0007`'s
   invariant intact: still one stack, still one seam, shell still gets plaintext.
2. **Stack = RustCrypto (`ed25519-dalek`, `x25519-dalek`, `chacha20poly1305`, `hkdf`,
   `blake3`).** Pure-Rust, `no_std`-friendly, already the ecosystem Iroh lives in — no
   libsodium C dependency dragged across three platforms. `chacha20poly1305` replaces
   NaCl's XSalsa20-Poly1305 for the sealed-key/vault AEAD (equivalent construction, and
   `crypto_box` maps to `x25519` + this AEAD). BLAKE3 already required by `p2p-0019`'s
   segment integrity — reuse it for HKDF-adjacent hashing, don't add SHA.
3. **Native shells keep doing zero application crypto.** Android/desktop (Kotlin) and iOS
   (Swift) call the FFI, receive plaintext segments, feed the player. The **only**
   platform-side key op remains OS-keystore wrapping of the at-rest `secretKey` (Android
   Keystore / Secure Enclave / macOS Keychain) + biometric gating — platform APIs, not a
   crypto library. Unchanged from `p2p-0007`.
4. **Reject per-platform native crypto** (Kotlin JVM lib on Android + CryptoKit/Swift on
   iOS). That is the two-stack byte-for-byte interop layer `p2p-0007` explicitly rejected —
   exactly where silent crypto mismatches hide — reintroduced across the Android/iOS split.
   One Rust core compiled to both targets has no interop seam by construction.
5. **Reject a KMP/JVM crypto lib in shared Compose code** (e.g. Bouncy Castle). Same
   two-stack objection as `p2p-0007`, plus it splits key material out of the FFI seam the
   OS-keystore wrapping is designed around.

## Consequences

- **`p2p-0007` invariant preserved, mechanism swapped** — one audited stack, one seam,
  shell does zero crypto. The revisit is a relocation, not a redesign.
- **libsodium retired** — no C library across three FFI targets; RustCrypto rides the same
  toolchain as Iroh. One fewer native dependency to build and audit.
- **`p2p-0001` clear-key unchanged** — the envelope, threat model, and "determined user can
  extract the key" honesty all stand; only its execution site moved worklet → Rust FFI.
- **Encrypt-before-transport ordering (from `p2p-0019` §5) is now in one place** — segments
  are sealed by this module *before* handing to Iroh's byte streams; QUIC covers transport,
  this covers at-rest. Both live in the same Rust lib, so the ordering is a function call,
  not a cross-runtime hand-off.
- **`p2p-0025` device set fits cleanly** — the account identity key signing per-device Iroh
  node keys is an `ed25519-dalek` sign/verify in this same module; identity-key ops and
  transport-key ops share the stack without sharing the *keys*.
- **New spike sub-gate for `p2p-0019`** — confirm `iroh-ffi` (uniffi) can be extended with
  a viewrr crypto module exposed over the same Kotlin/JVM + Swift bindings, or that a
  sibling uniffi crate re-exports through one artifact. If uniffi can't cleanly host both,
  fall back to a thin second uniffi crate (still one Rust stack, two generated bindings).
- **Cost** — the envelope is re-implemented once in Rust (small: seal/open + HKDF +
  per-segment AEAD). One-time, POC-stage; cheaper than maintaining a JVM↔Swift interop
  layer forever.

## Open questions

1. **uniffi surface for crypto** — one crate with Iroh, or a sibling crypto crate? (spike
   sub-gate above). Prefer one crate if the FFI stays small.
2. **Keystore-wrapped `secretKey` → Rust** — the raw `secretKey` is unwrapped by the OS
   keystore on the platform side, then must reach the Rust module. Confirm it crosses the
   FFI as ephemeral bytes zeroized after load (`zeroize` crate), never persisted platform-
   side. This is the one place key material touches the seam; pin it in the spike.
3. **Vault format continuity** — `p2p-0006` Vault Link copies the `secretKey`; confirm the
   RustCrypto secretbox/`chacha20poly1305` vault format is what pairing writes, so there's
   no NaCl-secretbox-vs-RustCrypto migration for existing vaults (POC-stage: likely none
   exist yet, so just standardize on the Rust format now).

## References

- `p2p-0007` — single crypto stack in the worklet (**mechanism superseded; invariant kept**)
- `p2p-0001` — self-custody clear-key envelope (**unchanged; this ADR places its execution**)
- `p2p-0019` — Iroh P2P core (deletes the worklet; brings transport crypto; opens this revisit)
- `p2p-0025` — identity key ≠ Iroh node key (device-set signing shares this stack)
- `p2p-0006` — Vault Link pairing (vault format continuity)
