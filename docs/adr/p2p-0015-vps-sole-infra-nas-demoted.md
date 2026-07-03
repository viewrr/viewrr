# 0015 — VPS is viewrr's sole server-side infra; NAS demoted to peer + Backup Tier

**Status:** Accepted (2026-07-03). Extends `p2p-0014`.

## Context

Docs `12-mesh-telemetry` and `13-database-ha` are written around "**jobin-nas = the
infra hub**" — Postgres primary, Ktor, DHT bootstrap all on the NAS, with a VPS as
replica. Three prior decisions dissolve that premise: bootstrap moved to the VPS
(`p2p-0014`), the NAS is not a content origin (`p2p-0005`), and the critical dataset
(users/entitlements/payments) is **~MB even at 10k users** (`p2p-0008`) — nothing about
it needs to live on the NAS. Pinning it there buys only residential ISP/power/NAT risk;
doc 13 itself budgeted ~5% primary downtime (~18 days/yr of failover churn).

## Decision

1. **Postgres primary = VPS.** Patroni cluster is **VPS1 (primary) + VPS2 (replica) +
   a small third etcd-only witness** (Fly.io or a €3 CX11) for quorum. The **NAS is
   dropped from the PG cluster entirely.**
2. **Ktor + catalog (ParadeDB) co-locate with Postgres on the VPS** — no cross-network
   hop per query, no residential API host.
3. **The VPS control plane stores zero content** — catalog metadata + critical tables
   only. Content lives solely on users' own device Storage Pools (`p2p-0011`).
4. **The NAS is demoted to a normal peer** plus the optional **Backup Tier**
   (ciphertext-only, the user's own pool). It has no network-wide infra role.

## Consequences

- viewrr has **no residential infra dependency** — server-side availability is a
  datacenter concern only.
- Docs 11/12/13's "jobin-nas as infra hub" framing is superseded; treat the NAS in
  those docs as one example user pool member, not viewrr infrastructure.
- Timescale telemetry (phase-2, `#161`) lands on the VPS Postgres, not the NAS.
