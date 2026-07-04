# 0023 — Monetization suite overview (payments + storage), index of p2p-0020…0022

**Status:** Accepted (2026-07-04) — index / through-line for the monetization ADRs

## Purpose

A single entry point to viewrr's monetization design. The detail lives in three
ADRs; this one records the **invariants they all share** and how they fit together,
so a reader (or a new agent) starts here.

## The suite

| ADR | Scope | One line |
|-----|-------|----------|
| [p2p-0020](p2p-0020-seeder-bandwidth-payments-opt-in-usdc-channels.md) | **Bandwidth payments** | Optional, peer-settled pay-a-seeder-for-priority: opt-in wallet, USDC on L2 with payment channels, market-priced cheapest single-source paid pull, per-segment pay-on-verified-delivery, proportional QoS, best-effort live ETA. |
| [p2p-0021](p2p-0021-storage-marketplace-smart-contract-escrow.md) | **Storage marketplace** | Sell pooled GB (not media): non-custodial smart-contract escrow, margin as a coded protocol fee, erasure-coded slices, deal migration (handoff-before-release), the contract itself as the SPOF-free job queue. |
| [p2p-0022](p2p-0022-storage-supply-hybrid-and-go-settlement-service.md) | **Supply model + code split** | Hybrid supply (device hot tier + Sia/Filecoin durable backstop); all settlement in a new **Go** sister service, mesh data plane stays Kotlin/worklet, gRPC boundary. |

## Shared invariants (true across all three)

1. **Non-custodial, always.** viewrr is a *protocol*, never a money transmitter — it
   never holds, routes, or nets user funds. Payments are peer-settled or escrowed
   on-chain; viewrr's margin is a coded protocol fee. This is the legal shield and it
   is non-negotiable (reaffirms `p2p-0010`).
2. **Opt-in, free tier intact.** Nobody is forced to touch crypto. Free serving is
   the default; the mesh works fully for free (`p2p-0005`, mission). A wallet is
   created only when a user opts in, in profile settings.
3. **USDC for users and contributors.** All user-facing and contributor payments are
   USDC on an L2. The only non-USDC leg is viewrr paying the Sia/Filecoin backstop in
   its native token from viewrr's own margin — a vendor payment, invisible to users.
4. **Pseudonymity scoped, not lost.** A *paying* pair reveals its wallet addresses to
   each other; the server learns nothing; non-paying users keep full pseudonymity
   (`p2p-0006`/`p2p-0008`).
5. **Integrate over build.** Proof-of-storage, repair, and deal migration are solved
   by Sia/Filecoin — integrate them for the durable tier rather than build
   proof-of-spacetime solo.

## Where the code lives

- **Kotlin `:server` / worklet / `:client`:** the mesh data plane (segment serving,
  device storage pool `#127`, erasure-coded replication). No money code.
- **New Go sister service (`viewrr-pay`):** escrow contract, USDC, payment channels,
  proof verification, deal-migration matcher, Sia/Filecoin backstop. Talks to the
  Hub over gRPC; the only component that touches chains and storage networks.

## Biggest unresolved risks (see each ADR's open questions)

- **Content-law exposure** of paying for copyrighted bytes (`p2p-0020`) — get counsel.
- **Recurring-rent vs one-time** economics for storage (`p2p-0021` OQ2).
- **Which backstop network** + USDC↔token swap (`p2p-0022` OQ1).
- **Get real legal review** before shipping any money movement.

## Status of implementation

Design only. No monetization code exists yet in either repo. The Go service is a
fresh repo (`~/Project/viewrr-pay`) scaffolded from these ADRs.
