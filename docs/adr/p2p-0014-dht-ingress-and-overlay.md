# 0014 — DHT bootstrap ingress on public VPS; internal mesh via overlay; media stays P2P-direct

**Status:** Accepted (2026-07-03)

## Context

The self-hosted DHT bootstrap plan (doc `11-tether-risk`) placed bootstrap nodes on
jobin-nas behind Nebula and assumed a public UDP port-forward. But jobin-nas fronts its
services through a **Cloudflare tunnel, which carries HTTP/S only — no raw UDP** — so it
cannot expose HyperDHT's UDP 49737 to external peers. This forced an explicit split of
what needs a *public* endpoint from what only needs a *private* one.

## Decision

1. **DHT bootstrap runs on a public-IP VPS**, not the NAS. HyperDHT bootstrap must be
   reachable by arbitrary external peers on the public internet; the VPS has a real IP
   and unrestricted UDP. Hybrid order unchanged (our nodes first, Holepunch fallback).
2. **Internal infra mesh** (NAS ↔ VPS ↔ Ktor, Patroni, health checks) uses a WireGuard
   overlay — **Headscale, or plain WireGuard** for the ~3 boxes. This is the "Infra
   plane"; doc 11's "Nebula" naming is replaced.
3. **User↔user media is P2P-direct** (Hyperswarm holepunch between devices) and **never
   relays through viewrr infrastructure.** No overlay/tunnel touches user media — NAS is
   not a content origin (p2p-0005 point 1). Tunnels only carry NAS-side traffic (Ktor
   API over CF, optional Backup-Tier ciphertext).
4. **Pangolin** (public-VPS reverse tunnel, raw TCP/UDP) is the fallback *only* if
   bootstrap must physically live on the NAT'd NAS. Default is bootstrap-on-VPS, no
   tunnel hop on the latency-sensitive UDP path.

## Considered and rejected

- **Bootstrap on NAS via CF tunnel** — impossible; CF tunnel is HTTP-only.
- **Nebula / Headscale / NetBird as the public ingress** — category error. All three are
  *private* overlays; external random peers cannot join them, so none can serve public
  DHT bootstrap. They solve the internal plane only.
- **NetBird + Keycloak** for the overlay — overkill for ~3 machines, and viewrr retired
  Keycloak (#150; identity is Ed25519 pubkey). Ravencloak is a separate project
  (p2p-0013), not viewrr's machine-mesh auth.

## Consequences

- Bootstrap availability now depends on the VPS, not the NAS — acceptable, the VPS is
  already the HA/replica host (doc 13, phase-2).
- `BOOTSTRAP_NODES` shipped in the app binary point at the VPS DNS names
  (`dht1/dht2.viewrr.app`), Holepunch nodes as IP fallback.
