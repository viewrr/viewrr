# 0024 — Mesh-ID recovery: BIP39 seed + passkey(PRF)-wrapped backup, provider-blind

**Status:** Proposed (2026-07-05). **Extends `p2p-0013`** (account registry / directory —
identity, not recovery). Spike-gated.

## Context

viewrr's account is a **self-custody Ed25519 key** — `publicKey = account` (`p2p-0013`).
Self-custody's failure mode: **lose the private key → lose the account**, with no reset. The
user wants to **recover their identity on a new device / after logout** — ideally via
passkeys.

Two constraints from existing decisions bound the design:
- **No PII / no custodian** — the directory is pseudonymous and PII-free (`p2p-0013`), and
  device fingerprinting is rejected (`p2p-0012`). A recovery path that hands a third party
  (Google, an IdP) the ability to gate or read the identity **breaks self-custody** and
  re-introduces PII.
- **Recovery must not become custody transfer** — the whole point is recovering *without*
  surrendering the key to anyone.

A tempting wrong turn — **NetBird / mesh-VPN with SSO** — is rejected up front: its SSO
authenticates a **device into a WireGuard network** (network-access identity), which is a
different layer from viewrr's **content-ownership** Ed25519 key. It would recover VPN
membership, not the account key, and its "P2P mesh" is a private device VPN, not viewrr's
content mesh (`p2p-0019`). Wrong tool.

## Decision

1. **The key derives from a BIP39 seed.** The Ed25519 account key is derived from a mnemonic.
   The seed phrase is the **zero-custodian recovery floor** — back up the words, re-enter
   anywhere, no third party involved. This always works even if everything below fails.
2. **Convenience recovery = passkey with the WebAuthn PRF extension.** A passkey's PRF
   derives a stable symmetric key → **encrypt the seed with it** → store the **ciphertext**
   in the directory (`p2p-0013`). On a new device the user authenticates the passkey → PRF
   re-derives the key → decrypts the seed → identity restored. **Passkeys already sync across
   the user's devices** (iCloud Keychain / Google Password Manager), so "new device / after
   logout" works — and the platform/provider stores only **opaque ciphertext**, never the key.
3. **Libraries, not a product.** Server: **webauthn4j** (Kotlin/JVM, drops into Ktor). Client:
   platform passkey APIs (Android Credential Manager, iOS AuthenticationServices) via Compose
   MP **expect/actual**. No IdP product, no mesh-VPN.
4. **Social login = optional, provider-blind, discouraged.** If offered at all, an OIDC
   provider may gate access to a **provider-blind encrypted blob** (the IdP cannot read the
   key) as a *fallback* recovery — never the default, clearly weaker (re-adds a custodian +
   PII linkage, in tension with `p2p-0012`/`p2p-0013`). Passkey + seed is the primary path.
5. **NetBird / mesh-VPN rejected** — wrong identity layer (network access ≠ ownership key),
   redundant mesh (`p2p-0019`).

## Consequences

- **Self-custody preserved** — recovery never surrenders the key. The seed is the floor; the
  passkey is UX sugar over an encrypted backup; no custodian can impersonate or lock out.
- **No PII added** (passkey/seed path) — the directory keeps storing only opaque ciphertext,
  consistent with `p2p-0013`/`p2p-0012`.
- **Platform passkey sync is the new-device mechanism** — leans on iCloud/Google passkey sync,
  which is a soft dependency on the platform keystore (acceptable; the seed is the escape
  hatch if a user has no synced passkey).
- **Client work = expect/actual passkey integration** (WebAuthn PRF must be available on the
  target passkey providers — verify coverage). Server work = a webauthn4j registration/auth
  flow + a ciphertext blob column in the directory.
- **Social login, if built, is extra surface** with a custodian — kept optional and blind so
  it can be dropped without affecting the primary path.

## Spike gate (before Accepted)

1. Confirm **WebAuthn PRF** is available on the target passkey providers (Android Credential
   Manager + iOS AuthenticationServices) for the platforms viewrr ships; if PRF is missing on
   a target, fall back to seed-only there.
2. End-to-end: register a passkey → PRF-wrap a BIP39-derived seed → store ciphertext in the
   directory → **recover on a second device** via synced passkey → decrypt → same Ed25519
   pubkey.
3. Confirm the **seed-phrase floor** recovers with no passkey at all.

If (1) and (2) hold → promote to **Accepted**; leave social login as a separate, later,
optional ADR only if a real need appears.

## References

- `p2p-0013` — account registry / directory (identity; this ADR adds recovery)
- `p2p-0012` — no device fingerprinting (why social/PII recovery is discouraged)
- `p2p-0019` — Iroh P2P core (why NetBird's mesh is redundant; Ed25519 node key = account key)
- webauthn4j (JVM/Kotlin WebAuthn), WebAuthn PRF extension, BIP39
