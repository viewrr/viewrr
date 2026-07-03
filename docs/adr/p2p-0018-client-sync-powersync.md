# 0018 — Client sync layer = PowerSync (Postgres→SQLite), keeps ParadeDB; covers catalogue subset + user state

**Status:** Proposed (2026-07-04). **Supersedes `p2p-0017`** (Turso catalogue replicas).
**Extends `p2p-0008`** — server catalogue authority (ParadeDB) is unchanged. Spike-gated
(§Spike gate).

## Context

`p2p-0017` chose **Turso Database** to push a read-only catalogue replica to clients. Two
costs made it expensive: (1) it forced a **second server-side store** (libSQL/Turso derived
from ParadeDB via a rebuild/CDC pipeline) — dual authority; (2) the Turso rewrite is
**beta** (eager memory load, experimental sync/FTS). It also left the *other* client-data
problem — **user state** (library, watch progress, playlists across the device pool,
`p2p-0011`) — entirely unaddressed.

**PowerSync** changes the shape of the answer. It syncs **from Postgres** (logical
replication / WAL CDC) **to client-side SQLite**, keeping Postgres as sole authority.
Relevant properties:

- **Postgres stays the authority** — ParadeDB / `pg_search` (`p2p-0008`) is untouched. No
  second store, no dual authority.
- **Client SDKs are Apache-2.0**, including a **Kotlin Multiplatform** SDK (Android / iOS /
  Desktop) — matches the Compose Multiplatform client (`p2p-0002`, `ADR-0005`).
- **Open Edition is self-hostable** and can use **Postgres for internal bucket storage** (no
  MongoDB dependency) — satisfies neutral-infra (`p2p-0005`).
- **Sync Rules / buckets** filter server-side and form a security boundary; clients only
  receive their buckets. **Bidirectional** with a persistent offline **upload queue**.
- **Hard constraint:** local SQLite is sized for **thousands–tens-of-thousands of rows per
  client, not millions.** Large buckets → slow initial sync + device storage bloat.

One sync engine, correctly scoped, serves **both** viewrr client-data problems over the
**one** Postgres+ParadeDB authority already decided.

## Decision

1. **Client sync layer = PowerSync (Postgres→SQLite CDC).** Postgres + ParadeDB + Patroni
   (`p2p-0008`, `p2p-0015`) stays the sole server authority. Turso and libSQL are dropped.
2. **Self-host PowerSync Open Edition on the VPS**, with **Postgres bucket storage**
   (`p2p-0005` neutral infra — no hard dependency on PowerSync Cloud). No new datastore
   type: the PowerSync Service is a new *process*, not a new *authority*.
3. **KMP SDK in the Compose Multiplatform client** (Android/iOS/Desktop). Local reads are
   plain SQLite.
4. **Two bucket classes, deliberately different:**
   - **Catalogue bucket — SCOPED, read-only.** Sync only a *subset* the client needs
     offline: recently-added + the user's region + favorites / continue-watching. **Not** a
     full-catalogue broadcast — that would blow PowerSync's row limit. **Full deep search
     stays server-side via the ParadeDB BM25 API** when online.
   - **User-state buckets — per-user, bidirectional.** Library, watch progress, playlists.
     Per-user row counts sit in PowerSync's sweet spot. Offline edits queue locally and
     reconcile across the device pool (`p2p-0011`) via the upload queue.
5. **Client local search = SQLite FTS5** over the synced catalogue subset (offline,
   good-enough ranking). **Authoritative BM25 search remains ParadeDB** server-side.
6. **Write direction:** only **user state** flows client→server (upload queue → an
   app-defined write endpoint → Postgres). **Catalogue stays server-ingested** — mesh
   contribution (`p2p-0008` §3) is unchanged; clients never write catalogue rows.

## Consequences

- **Server stack unchanged** — Postgres + ParadeDB + Patroni. This is the whole point:
  `p2p-0017`'s dual-authority + rebuild/CDC pipeline disappears, and `p2p-0008`/`p2p-0018`
  rejects (Cockroach/rqlite) stand.
- **One engine, two problems** — catalogue-subset offline browse *and* offline-first user
  state, in one self-hosted service with one client SDK.
- **New self-hosted component** — the PowerSync Service runs alongside Postgres on the VPS.
  This is the real added surface (comparable to `p2p-0017`'s libsql-server, but production
  and Postgres-native).
- **Deep search is online-only** — offline clients search their FTS5 subset; full-catalogue
  relevance requires the server ParadeDB API. Acceptable if the offline slice is well-chosen.
- **Row limit dictates scoping** — the catalogue bucket **must** be a subset. If the product
  genuinely requires the **entire** catalogue available offline, PowerSync is the wrong tool
  and the fallback is `p2p-0017`'s broadcast model (Turso) or server-only search.
- **Lower lock-in than the superseded plan** — client SDKs Apache-2.0, Service source-
  available Open Edition (self-host free), Postgres remains portable. No bet on a beta engine.

## Spike gate (before Accepted)

1. **UX check first:** confirm a **scoped** catalogue bucket (recent + region + favorites)
   covers the offline browse experience. If users expect the *whole* catalogue offline →
   PowerSync is wrong here; revert catalogue distribution to `p2p-0017` (Turso broadcast) or
   go server-only. User-state sync stays PowerSync regardless.
2. Self-host PowerSync Open Edition + Postgres bucket storage on the VPS; sync one client
   via the KMP SDK.
3. Measure **initial sync time + device storage** for the scoped catalogue bucket on the
   weakest client (low-end TV/phone).
4. Validate an **offline user-state write** (e.g. add to library) reconciling across **two**
   devices in the same pool.

If (1) holds and (3)/(4) are clean → promote to **Accepted**.

## References

- `p2p-0017` — Turso catalogue replicas (**superseded by this ADR**)
- `p2p-0008` — central catalogue, ParadeDB/pg_search, mesh-contributed (authority, unchanged)
- `p2p-0005` — neutral infra, user-hosted (self-host PowerSync)
- `p2p-0011` — multi-device storage pool (user-state sync target)
- `p2p-0015` — VPS sole infra, zero content (PowerSync Service co-locates with Postgres)
- PowerSync: https://powersync.com/ · KMP SDK: https://docs.powersync.com/client-sdk-references/kotlin-multiplatform
