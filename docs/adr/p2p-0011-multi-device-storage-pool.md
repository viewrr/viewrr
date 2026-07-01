# 0011 — Multi-device storage pool (per-device ≥20% free space)

**Status:** Accepted (2026-07-01). Durability sub-decision resolved (see Decision 4-6).

## Context

A user's Identity (`P2P-ADR 0001`) spans multiple devices, discovered via the private,
secret-derived sync topic (`P2P-ADR 0006`). viewrr has no central media server (`P2P-ADR 0005` pt1),
so a user's own devices must host that user's private vault **and** the public content
they seed.

## Decision

1. **Each device installation declares its free storage** and dedicates a **minimum
   20%** of free space to viewrr.
2. **The dedicated slices across a user's devices form a single user-scoped storage
   pool.** The user's private content and their publicly-seeded content live in this
   pool.
3. The pool is the unit that hosts and seeds the user's catalogue — private (own
   devices only) and public (mesh-visible) — with no central origin.
4. **Private originals: replication factor ≥2** across pooled devices whenever ≥2
   exist. **Never RF=1** for private originals. Private originals are **never evicted**
   and take pool priority; public cached content is **RF=1, LRU-evictable** (it is
   re-fetchable from the mesh). When the pool nears capacity, evict public cache first.
5. **Single-device users get a loud warning** ("data is on one device only") — no
   silent data-loss risk. Overflow beyond pool capacity requires adding a device or the
   backup tier; never a silent drop.
6. **Encrypted backup tier is in MVP** as the single-device durability escape hatch.
   The NAS stores the user's **ciphertext-only** originals (no key, no plaintext, no
   backdoor — consistent with `P2P-ADR 0010`). Backup ships functionally in MVP on jobin-nas;
   **billing is deferred to phase-2 payments** (`P2P-ADR 0005` pt3 intact — free/self-hosted
   during MVP, monetized later).

## Consequences

- **Good:** Storage scales with the user's own device fleet; no operator-hosted
  storage. Fits self-custody + no-central-origin.
- **Open sub-decision (to grill):** pool **replication vs distribution**:
  - *Replicate* every file to all pooled devices → offline-tolerant, but total capacity
    capped near the smallest device's slice.
  - *Distribute/shard* across devices → more capacity, but a file is unavailable when
    its host device is offline.
  - **Durability risk:** a user's **private originals exist only in the pool.** If they
    are sharded with replication factor 1 and a device is lost/wiped, data is gone.
    Public cached content is re-fetchable from the mesh (safe to evict); private
    originals are not. The eviction/replication policy must protect private originals.
- **Dynamic floor:** "≥20% free" changes as the disk fills; needs a declared floor plus
  graceful handling when a device approaches capacity (relates to TTL/tiers, `Part 8`).
