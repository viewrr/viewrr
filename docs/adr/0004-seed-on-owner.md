---
status: accepted
---

# Seed acquired torrents on the Owner, not the Downloader

In the acquisition flow the fastest box (**Downloader**) fetches a torrent, then
hands the finished file to the triggering box (**Owner** — typically the home NAS),
which seeds to a 1:1 ratio while the Downloader deletes its copy. We deliberately
seed on the **Owner** rather than the (faster) Downloader because the Owner is the
permanent home of the file and the intended swarm-visible participant.

## Considered options

- **Seed-on-Downloader-then-handoff** (the conventional seedbox pattern) — reach 1:1
  fast on the fast box's uplink, spare the home link, then copy to the Owner and
  delete. Rejected per the Owner-is-the-citizen intent above.

## Consequences

- Hitting 1:1 is **slow** (bounded by the home uplink) and **saturates the home
  link** for the duration.
- The **home IP** is the swarm-visible seeder, not the Downloader (often a VPS) —
  an exposure trade-off chosen knowingly.
- Ratio counters reset on handoff; 1:1 is measured on the Owner.
- **No-gap invariant:** handoff carries the torrent metadata + data; the Owner
  force-rechecks and is announcing **before** the Downloader's copy is removed —
  the swarm never loses its last seed.
- This is the surprising decision (inverse of normal seedbox practice); the ADR
  exists so nobody "optimises" it back to seed-on-Downloader without knowing why.
