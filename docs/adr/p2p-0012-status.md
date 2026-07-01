---
status: rejected
issue: viewrr/viewrr#129
p2p-adr: 0012
milestone: P2P / Self-Custody Re-architecture
---

# No device/browser fingerprinting for Sybil resistance (rejected)

Device/browser fingerprinting (canvas hashing, user-agent hashing, `navigator`
entropy, `deviceId` derivation) as a Sybil-resistance mechanism is **rejected**.
This is a decision record, not a feature — nothing is built.

## Why rejected

- **Contradicts permissionless identity.** The P2P self-custody re-architecture is
  built on identity that anyone can hold without gatekeeping. Fingerprinting is a
  covert per-device gate — the opposite of permissionless.
- **Privacy-hostile.** Silent client fingerprinting tracks users without consent and
  is exactly the surveillance pattern a self-custody media server exists to avoid.
- **Only touches the web tier.** Fingerprinting is a browser-side signal; native
  clients (AFinity/CMP mobile, TV) never produce it. A defense that only covers one
  of three surfaces is not a defense.
- **Evadable.** Fingerprints are trivially spoofed/rotated (headless browsers,
  UA overrides, VMs). It raises effort for honest users far more than for a Sybil
  attacker.

## Alternative (where integrity actually lives)

Catalogue integrity is enforced at the **data layer**, not the client:

- **TMDB allowlist** — titles are validated against TMDB identity (see issue #128 /
  title-copy-identity split, ADR 0002).
- **Content checks** at ingest, at the same layer that owns the catalogue.

This puts integrity next to the data it protects and covers every client uniformly.

## Repository state

Grep for `fingerprint`, `canvas`, `deviceId`, `user-agent` hashing, and
`navigator.userAgent` found **no fingerprinting code**. The only `deviceId` usage is
in the downloads subsystem (`server/src/main/kotlin/downloads/*`), where it is a
client-supplied key for organizing per-device MP4 downloads — unrelated to
fingerprinting. **Nothing to remove.**

## Consequences

- No fingerprinting is added now or planned.
- Sybil/integrity concerns route to the data-layer controls above; revisit there,
  not in the web client, if they resurface.
