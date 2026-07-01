# 0008 — Central searchable catalog, mesh-contributed; viewrr is the index

**Status:** Accepted (2026-07-01). Supersedes `P2P-ADR 0005` point 2.

## Context

viewrr is positioned as a Netflix-alternative SVOD. The defining feature: **media
files are decentralized (no central media server), but the catalog is centralized and
searchable.** Anything downloaded/cached by any peer in the mesh contributes its
metadata + availability to a central catalog, so the catalog grows organically from
mesh activity and every title becomes findable.

This reverses `P2P-ADR 0005` point 2 (which removed the central catalog to preserve a neutral
posture). The neutral-transport property still holds for the *files*; it does **not**
hold for the *index* — hosting a searchable index of user-hosted third-party content
is the historically seized layer (indexes lose; protocols don't).

`P2P-ADR 0005` points 1 (NAS is not a content origin) and 3 (payments deferred) still stand.

## Decision

1. **Central catalog exists and is searchable**, powered by **ParadeDB / pg_search**
   (BM25 full-text). pg_search returns to the MVP stack.
2. **Content identity = TMDB ID → deterministic UUID v5** (already specced). Same title
   from different uploaders maps to one catalog entry — dedup by content UUID.
3. **Mesh-contributed:** when a client acquires/caches a UUID-identified title, it
   upserts the catalog entry (metadata) and registers availability. Files stay
   encrypted + P2P; only catalog metadata + availability is central.
4. **Timescale / PgBouncer remain deferred** — no time-series workload yet; HikariCP
   covers pooling at MVP scale.

## Consequences

- **Good:** Netflix-like browse/search over a decentralized file mesh — the product's
  core differentiator.
- **Legal:** viewrr is now unambiguously **the index**. Auto-contribution makes it a
  comprehensive index of whatever the mesh holds — maximal exposure. Requires a
  **takedown/DMCA pipeline** for catalog rows (removing an index entry ≠ removing the
  file) and a hosting entity/jurisdiction that can absorb notices. Accepted, eyes open.
- **Resolved:** availability is **pseudonymous**. The catalog stores **content
  metadata only** (`contentUUID`, title, poster, tags — no `publicKey ↔ title`).
  Peer discovery is via the **DHT** (`hash(contentUUID)` swarm), like BitTorrent;
  the server never learns who holds what. New catalog rows are **validated against
  TMDB** to prevent poisoning. Peer *selection* is client-side by Plus Code proximity +
  uplink speed (`04`). No central who-watched-what DB exists.
