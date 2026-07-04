# 0022 — Storage supply is hybrid (device hot tier + Sia/Filecoin backstop); settlement lives in a separate Go service

**Status:** Accepted (2026-07-04)

## Context

`p2p-0021` defined a pooled-storage marketplace and repeatedly landed on
"integrate, don't build" for proof-of-storage and repair. But "just use Sia"
hides a supply-side mismatch: every decentralized storage network (Sia, Filecoin,
Storj) assumes the *supply* is **semi-reliable nodes with real capacity, uptime,
and collateral**, settling in **their own volatile token** (SC / FIL / STORJ) —
**not** churny consumer/mobile devices contributing 2–3 GB, and **not** USDC. A
Pixel 7 with 2 GB spare is not a viable Sia host. So no existing network fits
viewrr's "every member's device contributes a slice" vision, and none settle in
the USDC the payment layer (`p2p-0020`/`p2p-0021`) was designed around.

This ADR resolves two things: the **storage supply model**, and **where the
payment/settlement/backstop code lives**.

## Decision

1. **Hybrid supply — device hot tier + Sia/Filecoin durable backstop.**
   - **Hot tier = contributed member devices** (phones, desktops): cheap,
     best-effort, high-replication (erasure-coded, RF≥2 per `p2p-0011`), tolerant
     of churn. This is viewrr's own mesh and keeps the member-contribution vision.
   - **Durable backstop = Sia/Filecoin** (the `p2p-0011` "encrypted backup tier"):
     the one thing consumer devices can't provide — *guaranteed* durability for data
     that must survive mobile churn. viewrr rents this from the network.
   - So viewrr does **not** "go with Sia" wholesale; Sia/Filecoin is the durability
     backstop *under* the device mesh, not the contributor supply.
2. **USDC payment infra is unaffected for users and contributors.** All user-facing
   and contributor payments (bandwidth, hot-tier storage) stay **USDC** exactly as
   `p2p-0020`/`p2p-0021` specify. The backstop settles in the network's token
   (SC/FIL), but that is **viewrr paying an infrastructure vendor for durability**,
   funded from viewrr's protocol-fee **margin** (like an AWS bill). The Go service
   performs the USDC→SC/FIL swap internally; **users never see it**. It is viewrr's
   own treasury paying a supplier — **not** custody of user funds, **not** money
   transmission (preserves the `p2p-0020` non-custodial shield).
3. **Payment + settlement + backstop live in a separate Go service/repo; the mesh
   data plane stays in Kotlin/worklet.**
   - **Kotlin `:server` / worklet / `:client` (existing mesh):** the contributor
     hot-tier **data plane** — device storage pool, erasure-coded replication,
     segment serving (`#127` / the p2p-0011 pool). *Not* payment infra; stays put.
   - **New Go sister service/repo:** *all settlement* — the USDC escrow contract
     interaction, proof-of-storage verification, deal state + migration matcher
     (`p2p-0021` Decisions 6/7), and the Sia/Filecoin backstop integration (storage
     deals + SC/FIL payment).
   - **Boundary:** the Kotlin Hub calls the Go service over **gRPC** for anything
     money / settlement / backstop; the Go service is the only thing that talks to
     chains, escrow contracts, and Sia/Filecoin.

## Rationale

- **Go for settlement:** geth/web3, Sia (`renterd`), and Filecoin (Lotus) are all
  **Go-native** — integrating any of them is far easier in Go than Kotlin/JS.
- **Isolation of money code:** a bug or exploit in payments must not reach media
  serving; the audit surface stays contained. This is the one place a separate
  service earns its complexity even for a solo builder.
- **Hybrid keeps the vision + buys durability:** members still contribute and earn;
  Sia/Filecoin covers the durability gap consumer devices structurally can't.

## Considered options

- **(a) Reseller — rent only from Sia hosts, drop member contribution (rejected):**
  abandons the member-contributor vision; viewrr becomes a thin Sia reseller.
- **(b) Build our own mesh storage end-to-end incl. proofs (rejected as primary):**
  research-grade proof-of-spacetime; borrow the erasure-coding libs, not the whole
  stack.
- **(c) Hybrid backstop (chosen):** device mesh for the cheap hot tier, Sia/Filecoin
  for guaranteed durability.
- **Payment code inside the Kotlin monolith (rejected):** no money-code isolation,
  and forces awkward JVM bindings to Go-native chain/storage tooling.

## Consequences

- **New repo + service to run** (Go): another deploy, CI, and a gRPC contract to
  maintain — real overhead for a solo dev, accepted for the isolation payoff.
- **A currency boundary inside the Go service** (USDC ↔ SC/FIL via a DEX/swap or a
  small viewrr-owned float) — an internal treasury operation, abstracted from users.
- **Two durability tiers to reason about** — best-effort device hot tier vs
  guaranteed Sia/Filecoin backstop; placement policy (what goes where) is a follow-up.
- **`p2p-0021` open questions narrow:** integrating Sia's `renterd` for the backstop
  provides its auto-repair there; the device hot tier still needs viewrr's own
  replication (already in `p2p-0011`).

## Open questions

1. **Which backstop network** (Sia vs Filecoin vs Storj) and the USDC↔token swap
   mechanism / float management.
2. **Tier placement policy** — what data lives only on the device hot tier vs is
   pushed to the durable backstop, and the cost/durability trade per tier.
3. **gRPC contract** between the Kotlin Hub and the Go settlement service (surface,
   auth, idempotency of settlement calls).
