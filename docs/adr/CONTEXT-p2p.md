# viewrr — Context Glossary

Ubiquitous language for the viewrr P2P SVOD platform. Definitions only — no
implementation. When a term here conflicts with usage in a design doc, this file wins
(or the conflict gets resolved and this file updated).

---

## Identity

The user's permanent cryptographic identity: an Ed25519 keypair. The `publicKey`
**is** the account (the "viewrr ID"). Derived solely from the **Recovery Phrase** —
never from the Master Password. The server never holds any part of the secret.

## Recovery Phrase

A BIP39 mnemonic (12 words default, 24 optional) that is the **sole root of trust**
for an Identity. `mnemonic → seed → DHT.keyPair` is the one and only identity
derivation. Entering the phrase on any device reproduces the exact same Identity.
Never transmitted, stored, or logged.

*Resolved Q1: the mnemonic — not the Master Password — is the identity root. The
earlier `Argon2id(masterPassword)→keypair` derivation is retired; it would have made
recovery reconstruct a different keypair.*

## Master Password

A **local unlock secret only**. Encrypts the at-rest `secretKey` (and vaults) on a
single device. Changing it re-encrypts the local blob and does **not** change the
Identity. Not synced, not the identity seed.

## Content Key

A per-title 32-byte AES key. Sealed to the user's `publicKey` on entitlement, opened
with their `secretKey` inside the Bare worklet, then used to derive per-segment
AES-128 keys + IVs via HKDF. This is **self-custody clear-key** protection — decrypt
happens in app memory. There is no hardware DRM and no license server (see
`docs/adr/p2p-0001`). Anti-capture is limited to OS window flags (forensic watermark
deferred out of MVP), not cryptographic prevention.

## Entitlement

The **permanent** right to a title, embodied by a self-custody Content Key sealed to
the user's `publicKey`. Once acquired, a title is **owned forever** — the server
cannot revoke it (it holds no `secretKey`). Playback is offline and client-side. See
`docs/adr/p2p-0004`.

## Subscription

A gate on **what a user may newly acquire/download**, plus seeding perks and storage
tier. A Subscription does **not** revoke already-owned Entitlements. viewrr is an
ownership model, not enforceable rental — despite legacy "SVOD" wording in older docs.
(Subscriptions/payments are deferred out of MVP — see `docs/adr/p2p-0005`.)

## NAS

Your homelab node. Its viewrr role is **DHT bootstrap + Ktor metadata registry** only
(plus an optional *paid* backup tier later). It is **not** a content origin/seeder —
content originates from users' own devices. See `docs/adr/p2p-0005`. (Legacy docs calling
the NAS "origin seeder of all content variants" are superseded.)

## Catalog

The **central, searchable index** of titles available in the mesh, powered by ParadeDB
/ pg_search. Media files are decentralized (no central media server); the **catalog is
centralized**. Any peer acquiring a title contributes its metadata + availability, so
the catalog grows from mesh activity. Titles are keyed by **content UUID** (TMDB ID →
UUID v5) so the same film from different uploaders dedups to one entry. viewrr *is* the
index — this is the product's core SVOD differentiator, and its main legal exposure
(needs a takedown pipeline). See `docs/adr/p2p-0008` (supersedes the earlier "no catalog"
stance in `P2P-ADR 0005`).

## Availability

The fact that some peer holds a given title. Discovered **P2P via the DHT**
(`hash(contentUUID)` swarm), never from a central `publicKey ↔ title` table. All copies
of the same format are interchangeable sources — no copy is canonical. See `docs/adr/p2p-0008`.

## Peer Selection

Choosing *which* available peer to pull from: **nearest by Plus Code**, then **fastest
uplink**, with a fallback chain to the next-best peer. Entirely client-side. Governs
*where to pull*, not *what is authentic*. See `docs/adr/p2p-0009`.

## Storage Pool

The union of storage slices contributed by a user's devices — each device dedicates
**≥20% of free space**. The pool hosts and seeds that user's private vault and their
publicly-seeded content. There is no central media server; a user's own device pool is
the origin for their content. See `docs/adr/p2p-0011`.

## Backup Tier

An optional durability service: the NAS stores a user's **ciphertext-only** private
originals (no key, no plaintext, no backdoor). It is the single-device durability escape
hatch against total data loss. Ships functionally in MVP on jobin-nas; billing deferred
to phase-2. See `docs/adr/p2p-0011`.

## De-index

The operator's **only** moderation power: removing a title's row from the central
Catalog so it is no longer searchable. It does **not** delete the file — bytes remain
reachable via direct public link. No backdoor, no key escrow, no file deletion. See
`docs/adr/p2p-0010`.

## Channel (phase 2)

A creator-owned publishing space (SoundCloud/Dailymotion-style) for the creator's
**own rights-cleared media**. Channels are the paid layer, deferred to phase 2 — a
distinct opt-in publishing model layered on top of the neutral infrastructure base.
Not part of MVP.
