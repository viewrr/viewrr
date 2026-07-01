# 0001 — Self-custody clear-key content protection, no hardware DRM

**Status:** Accepted (2026-07-01)

## Context

viewrr's thesis is zero-infrastructure P2P, keypair identity, and "the server never
holds secrets." Content is delivered as AES-encrypted AV1 HLS segments over a
Hyperdrive swarm; the content key is sealed to the user's `publicKey` and opened
with their `secretKey` inside the Bare worklet (self-custody).

Two design docs also claimed Widevine L1 / FairPlay hardware DRM on the *same*
streams (black screen on capture, key never in app memory).

These are mutually exclusive:

- Hardware DRM (L1/FairPlay) requires the platform **CDM to own the decrypt+render
  path**, with keys delivered by a **license server** into a hardware TEE. The app
  never sees the key, and the anti-capture guarantee exists only because the TEE owns
  rendering.
- Self-custody decrypts in app memory and feeds the player — this is, by definition,
  clear-key / Widevine L3. No TEE, no black screen.
- There is **no license server** in the architecture, and adding one (always-on,
  key-holding) contradicts the zero-infra / self-custody thesis.

viewrr's content is its own catalog + user-owned files, not third-party
studio-licensed content that contractually mandates L1.

## Decision

1. **Content protection = self-custody clear-key.** Content key sealed to `publicKey`,
   opened with `secretKey` in the worklet, per-segment AES key + IV via HKDF.
2. **No hardware DRM, no license server.** Widevine L1 / FairPlay / PlayReady claims
   are removed from the spec.
3. **Keep OS-level capture flags** (`FLAG_SECURE` on Android, `UIScreen.isCaptured`
   black-screen on iOS) as zero-cost casual-capture deterrents — these need no DRM.
4. A future third-party-studio-licensed tier, if ever pursued, is explicitly a
   **different product mode** (DRM'd origin + license server, non-P2P for that
   content) and out of scope here.

## Consequences

- **Good:** Consistent with keypair identity + zero-infra P2P. No Google/DRM-vendor
  dependency, no per-playback license round-trip, no infra to run.
- **Good:** Honest threat model — we stop lying about "unbreakable" protection.
- **Bad:** A determined, entitled user can extract the content key and the plaintext
  stream. Deterrence rests on account-level trust + forensic watermark (see the
  watermark-vs-swarm-dedup decision) + capture flags — not cryptographic prevention.
- **Bad:** Cannot host content whose license contract requires L1. Accepted; not our
  content type.
