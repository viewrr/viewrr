# 0017 — Catalogue client distribution: read-only Turso replicas for offline browse/search

**Status:** Superseded by `p2p-0018` (2026-07-04) — PowerSync keeps Postgres+ParadeDB as
sole authority (no dual store), is production not beta, and covers user-state sync too.
Retained as history; the Turso broadcast model here remains the fallback if the product
requires the **entire** catalogue offline (which `p2p-0018`'s scoped-bucket approach does not).
Originally: Proposed, **extends `p2p-0008`**, spike-gated on client cold-start memory.

## Context

`p2p-0008` put the catalogue on a **central, server-authoritative ParadeDB / pg_search
store** (BM25), mesh-contributed and co-located with Postgres on the VPS (`p2p-0015`: the
VPS control plane holds catalogue metadata + critical tables, zero content). That ADR
decided *where the catalogue lives and how it grows* — it left open *how a client browses
and searches it*.

Two ways a client can search the catalogue:

1. **Server-side API search** (the lazy baseline) — every browse/search is a call to the
   VPS ParadeDB. Simple, one store, always fresh. But it makes catalogue browse depend on
   VPS reachability, which sits awkwardly with the self-custody / neutral-infra ethos
   (`p2p-0005`): if the datacenter is unreachable, the user can't even *see* their mesh.
2. **Client-side replica** — push a read-only copy of the catalogue to each device;
   browse/search runs locally, offline, single-digit-ms, and offloads the VPS.

The catalogue is **read-only on the client** (only the server ingests, via mesh
contribution — `p2p-0008` §3) and **grows continuously** as the mesh contributes titles.
Read-only + one-way + continuously-changing is the exact shape of an incremental
primary→follower sync. That points at an embedded-replica engine rather than a
hand-rolled REST delta protocol.

## Decision

1. **Distribute the catalogue to clients as a read-only local replica** for offline
   browse and local search. Server-side API search (`p2p-0008` ParadeDB) remains available
   and stays the authority; the client replica is a cache of it, never a write target.
2. **Engine = Turso Database** (the Rust rewrite — SQLite-compatible, in-process), not
   libSQL and not the legacy libSQL embedded-replica path. Chosen for its incremental
   sync with offline support and its Tantivy-based full-text search (BM25, phrase/prefix,
   index stored inside the DB file — a step up from SQLite FTS5's virtual-table model).
3. **Run the client DB non-MVCC.** MVCC (`BEGIN CONCURRENT` / concurrent writes) is
   **mutually exclusive with indexes** in the current beta — "databases with indexes
   cannot be used" under MVCC. The catalogue is read-only and needs its search + lookup
   indexes, so MVCC is neither needed nor usable here. We do not adopt Turso for its
   concurrent-write feature.
4. **Self-host the sync source on the VPS** (`p2p-0005` neutral infra — no hard dependency
   on Turso Cloud). The client-distribution DB is **derived from the authoritative
   ParadeDB catalogue** (periodic rebuild or CDC), not a second authority. Postgres +
   ParadeDB stays the ingest / mesh-contribution / DMCA-takedown store (`p2p-0008`).
5. **One-way only.** Clients use the *pull* half of Turso's bi-directional sync. No client
   writes flow back to the catalogue — mesh contribution stays the server's job. (User
   *state* — library, watch progress — is a separate problem for a later ADR; if that ever
   wants offline local writes, Turso's bi-directional + concurrent-write story is the
   right tool *there*, not here.)

## Consequences

- **Offline browse/search** — the catalogue is visible and searchable when the VPS is
  unreachable, matching the self-custody posture. Local search is instant and offloads the
  VPS ParadeDB.
- **Two catalogue copies + one new VPS service** — the authoritative ParadeDB copy plus a
  Turso publish-source, fed by a rebuild/CDC pipeline, running alongside Postgres. This is
  the real cost. Justified only if offline browse is an actual product requirement; if
  API-only search is acceptable, that baseline is lazier and this ADR should not land.
- **Beta risk, bounded.** Turso Database is beta ("use caution with production data, keep
  backups"), and the features leaned on — sync, Tantivy FTS — are experimental *within*
  the beta. The catalogue read path is the **most beta-tolerant surface in the system**:
  worst case, a client discards and re-syncs a fresh copy — nothing is lost, unlike
  payments (`Postgres`, never Turso). API churn during beta is expected.
- **Eager memory load is the gating risk.** Turso loads the dataset into memory on first
  access. A full-size catalogue on a constrained client (low-end TV / phone) could be
  slow to cold-start and memory-hungry. **This is what the spike must measure.**

## Spike gate (before Accepted)

Stand up a self-hosted Turso publish-source on the VPS, populate it from the ParadeDB
catalogue, sync **one** client embedded replica, and measure:

1. **Cold-start time + peak RAM on the weakest target client** (low-end TV/phone) with a
   full-size catalogue — the make-or-break number.
2. **Delta-sync bandwidth** on a representative catalogue update (mesh adds N titles).
3. **Tantivy FTS** relevance + latency vs the ParadeDB baseline.

If (1) is acceptable on the weakest client, promote to **Accepted**. If it is not, fall
back to server-side API search (`p2p-0008`) and revisit when Turso addresses eager
loading. Server-side ParadeDB authority is unchanged either way.

## References

- `p2p-0008` — central catalogue, ParadeDB/pg_search, mesh-contributed (authority)
- `p2p-0005` — neutral infra, user-hosted (self-host the sync source)
- `p2p-0015` — VPS sole infra, zero content (catalogue metadata lives on VPS)
- `p2p-0016` — segment-level client cache (precedent: read-only client caches of server-owned data)
- Turso Database (beta): https://github.com/tursodatabase/turso · FTS: https://turso.tech/blog/beyond-fts5
