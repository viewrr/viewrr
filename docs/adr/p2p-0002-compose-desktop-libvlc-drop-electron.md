# 0002 — Desktop client is Compose Multiplatform + libmpv, not Electron

**Status:** Accepted (2026-07-01) · **Amended 2026-07-03** — player is **libmpv**, not libVLC/vlcj (see Decision #2). Filename kept for link stability.

## Context

The design docs contradicted each other on the desktop shell: the architecture doc
assumed **Electron** (`systemPreferences.promptTouchID`, WebAuthn, `node-yubihsm`),
while the README listed **Compose Multiplatform** covering Desktop.

Goal: reduce total code and reuse one codebase. Codec direction is AV1 primary +
H.264 fallback (AV2 deferred until hardware decode lands, ~2026-2027).

## Decision

1. **Desktop shell = Compose Multiplatform (JVM)** — shares the KMP codebase with
   Android/iOS. Electron is dropped.
2. **Video player = libmpv** (amended 2026-07-03) — Compose Desktop has no built-in
   player; **libmpv is already the player in this repo** (the existing libmpv/AVPlayer
   path, #100). Reusing it keeps **one player per platform, no third** (#130, #163):
   macOS/iOS → AVPlayer, Android → Media3/ExoPlayer, Web/TV → hls.js, Desktop (JVM) →
   libmpv. libmpv is codec-agnostic (AV1 today, AV2 once it ships). The originally-named
   libVLC/vlcj is rejected — it would add a third player stack for no gain.
3. **Codec = AV1 primary + H.264 fallback** for MVP. AV2 is a later config-add rung.
4. A **segment-decrypt shim** is required regardless of player: the worklet decrypts
   each clear-key segment in memory and feeds the player, because the content key is
   deliberately absent from the HLS manifest.

## Consequences

- **Good:** One KMP codebase for Android + iOS + Desktop. Less code than a separate
  Electron app. libmpv handles all codecs including future AV2.
- **Good:** AV1 already delivers the "less data" goal without AV2's immaturity.
- **Bad / cascade:** Electron-specific plumbing must be re-homed on JVM —
  - Biometric/hardware-key unlock (Touch ID / Windows Hello / YubiKey) needs JVM
    paths, not Electron APIs. **Resolved:** MVP desktop unlock = **master password only**
    (the at-rest `secretKey` is already password-encrypted per `P2P-ADR 0001`; libsodium in the
    worklet). Desktop biometric + OS-keystore hardware binding are **deferred post-MVP**,
    added per-OS via native bridges (macOS LocalAuthentication+Keychain, Windows
    Hello+DPAPI/TPM, Linux password+keyring). Mobile biometric (Android Keystore, iOS
    Secure Enclave) is unaffected. Caveat: MVP desktop at-rest security = password
    strength only (no TPM/Enclave binding) — hardened later.
  - **The Bare worklet can no longer run in-process.** Electron could host the JS P2P
    core natively; a JVM app cannot. Desktop embeds Bare via a bundled subprocess +
    local-socket RPC — resolved in `P2P-ADR 0003`. This is the main new cost of this choice.
- **Note:** The Vue 3 **browser** client is unaffected — it remains a separate,
  degraded companion path, distinct from the Compose Desktop app.
