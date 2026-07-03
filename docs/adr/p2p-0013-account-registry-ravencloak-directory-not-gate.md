# 0013 — Account registry (Ravencloak) is a directory, not an auth gate

**Status:** Accepted (2026-07-03)

## Context

`#150` retired Keycloak/OIDC and made a self-custody Ed25519 key pair the sole
way to authenticate (register public key → challenge → signed verify → session
JWT). That closed the door on centralized *authentication* — correctly, for a
P2P system.

But three needs survive the retirement of centralized login:

1. **Account uniqueness** — a single authority that guarantees no two humans
   claim the same account name, and allocates it.
2. **Human-facing login UX** — a smooth path (including passkey/biometric
   unlock and, later, SSO) rather than asking a user to wrangle raw keys.
3. **A `@handle → publicKey` directory** — already implied by `p2p-0006`
   (mailboxes and discovery resolve a handle to a public key).

The temptation is to bring Keycloak back as the account authority. That would
reintroduce exactly the single point of failure `#150` removed: if the server
is down, no one logs in or plays. **Full offline/decentralized availability is
non-negotiable** and outranks the convenience of a central auth server
(`p2p-0005` neutral/user-hosted infra; `p2p-0010` operator power = de-index
only, no backdoor).

Two concerns were being conflated and are now separated:

- **Authentication** (proving control of a credential) — stays self-custody.
- **Account registry** (uniqueness, handle allocation, device grouping, login
  UX) — is where a central component adds real value.

Passkeys forced the sharpest question. True per-device WebAuthn keys are
non-exportable and cannot be seed-copied, which would force per-device keys +
delegation certificates + per-device revocation (a large subsystem). We
rejected that (Option 2) for now: the **wallet model** of `p2p-0006` is kept —
one Identity key per human, copied to each device at pairing — and a passkey
serves as the **local unlock** of the encrypted key at rest, not as a distinct
per-device identity. Every stated requirement (unique UID, central user
directory, smooth passkey login, multi-device same user, zero PII, full offline
availability) is met by this without new crypto infrastructure.

## Decision

1. **Ravencloak is the Account registry** — an eventual-consistent directory
   that allocates unique **Handles** (`@handle`) and indexes
   `@handle → publicKey`. It stores **no personally identifiable information**
   (pubkey-only realm; no social IdP linkage for viewrr accounts).
2. **Directory, not gate.** Ravencloak is authoritative *only* for Handle
   uniqueness and login/SSO experience. It issues **nothing** on the
   authentication or playback path.
3. **Authentication stays in viewrr's `IdentityService`** — challenge → verify
   signature → session JWT (HS256). An already-paired device authenticates and
   plays with the server offline; Ravencloak is never required at play time.
4. **Wallet model retained** (`p2p-0006`): one Identity key per human, copied to
   devices at pairing. A passkey/biometric unlocks the locally-encrypted
   `secretKey` — it is a local unlock, not a per-device identity credential.

## Considered options

- **A — Keycloak as root of trust (rejected):** canonical account = the server
  record. Down = no login, no play. Reintroduces the SPOF `#150` removed and the
  central human↔key correlation point `p2p-0010` guards against.
- **B — Pure self-custody, no directory (rejected):** no account uniqueness
  authority and no clean handle allocation; login UX and SSO become entirely
  bespoke.
- **C — Directory over self-custody keys (chosen):** central registry for
  uniqueness + UX; keys remain the root of trust; mesh and playback survive the
  registry being offline.
- **Option 2 within C — per-device WebAuthn + delegation certs (deferred):**
  buys per-device revocation and smaller blast radius; costs a delegation-cert
  subsystem. Revisit if revoking one device without rotating the whole Identity
  becomes a real requirement.

## Consequences

- **Narrows `#150`:** it is sole *authentication*, not sole account authority.
  A central Account registry exists again — but as a directory, never a gate.
- **`keycloak-db` volume was kept** at the 2026-07-03 teardown, so redeploying
  Ravencloak is unblocked (no data-loss recovery needed).
- **`p2p-0006` unchanged** — Option 1 keeps the copy-`secretKey` pairing.
- **`#142` (mobile bootstrap)** gains a step: after BIP39 → Ed25519 keygen, the
  client registers its Handle with Ravencloak (best-effort, non-blocking).
- **Blast radius accepted:** one compromised device compromises the shared
  Identity key; recovery = rotate the whole Identity. Upgrade path to per-device
  revocation is Option 2, deferred.
- **Availability invariant holds:** Ravencloak offline ⇒ registration/handle-
  claim pauses, but authentication and playback on paired devices continue.
