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

## Backups (catalogue + control-plane DB)

Patroni (`p2p-0015`) provides **HA, not DR** — it survives a node loss, not a dropped
table, corruption, or a bad migration. The Postgres cluster (catalogue metadata +
entitlements/payments) needs scheduled **point-in-time recovery** backups independent of
replication.

- **Use PHYSICAL backup (Full + Incremental + WAL, PITR), never rely on logical dump for
  the ParadeDB parts.** A physical file-level backup copies the whole cluster incl. the
  `bm25` index files — extension-agnostic. A logical `pg_dump` emits
  `CREATE INDEX … USING bm25`, which needs ParadeDB installed + version-matched on restore
  and can dump fragile; only viable with a reindex step. Indexes are rebuildable from data,
  so logical + reindex is a fallback, not the primary.
- **Restore verification must run against the ParadeDB image**, not stock Postgres — a
  physical restore of this cluster needs the extension `.so` present to start.
- **Destination = NAS** (already the ciphertext backup tier, `p2p-0011`) **or R2** —
  self-hosted, neutral infra (`p2p-0005`). Data is ~MB, so backups are cheap and fast.
- **Tool:** any physical-PITR tool works — `databasus` (tested-restore + UI, easier solo
  ops) or `pgBackRest` / `wal-g` (more battle-tested for the payments DB). Both do physical
  PITR; pick on operability vs proven-ness. Ops-layer choice, not architecture.
