# 0021 — Pooled-storage marketplace via non-custodial smart-contract escrow

**Status:** Accepted (2026-07-04) — core model; several mechanics deferred (see Open Questions)

## Context

Building on `p2p-0020` (optional peer-settled bandwidth payments) and `p2p-0011`
(multi-device storage pool, RF≥2), this ADR adds a second revenue product: **selling
pooled mesh storage**. A member contributes storage; other members (or outside
clients) can *buy* storage capacity from the pool for their own files/media.

Two deliberate framings:
- **Charge for storage-GB, not media files.** Selling dumb storage capacity (the
  Storj/Sia/Filecoin model) is a cleaner copyright posture than charging for the
  serving of specific (possibly copyrighted) content. This addresses the
  **copyright** exposure flagged in `p2p-0020`.
- **This is a distinct product** from media hosting: generic decentralized cloud
  storage. viewrr becomes "media mesh **+** storage marketplace."

The originally-proposed design routed all payments through a **central viewrr wallet**
(buyers pay viewrr, viewrr pays providers, keeps the spread). That was rejected: a
custodial account taking funds from A and paying B with a margin is **money
transmission** (MSB registration, state money-transmitter licenses, EU EMI, India
PA/PSP, KYC/AML on all users, custody liability) — reversing `p2p-0020` Decision #1
(non-custodial) and `p2p-0010` (operator = de-index only, no broker). Charging for
storage does nothing for *this* legal axis; the two "legal issues" (copyright vs
money-transmission) are orthogonal.

## Decision

1. **The "central viewrr account" is a smart contract, not a company wallet.**
   Storage payments flow into on-chain **escrow**; the contract auto-splits to
   providers and **skims viewrr's margin as a coded protocol fee**. viewrr **never
   holds, routes, or pays out funds** — the chain does. This keeps viewrr a protocol
   (not an MSB), preserving the `p2p-0020`/`p2p-0010` non-custodial shield while still
   delivering the arbitrage/margin the product needs.
2. **Protocol-fee arbitrage.** The contract enforces `buyer_paid > Σ(provider_payouts)`;
   the surplus funds gas + viewrr's margin. Transparent and on-chain, not a hidden
   custodial spread.
3. **Mandatory + optional contribution.** On joining the mesh a member dedicates a
   **mandatory minimum** `x` GB, scaled to device free space (per `p2p-0011`). On
   opting into payments they may dedicate **additional** `y` GB priced at their
   choice — typically their upload price (`p2p-0020`) plus a storage surcharge for
   storage-scarce devices. `x`+`y` from all members form the sellable pool.
4. **Buyers get erasure-coded slices, not one blob.** A purchase of N GB is satisfied
   from **many small segments across many providers** — never a single provider's
   single blob. Redundancy is **erasure coding (Reed-Solomon) + replication factor**
   (the `p2p-0011` RF model), so provider churn/offline segments don't lose data.
   ("RAID" was the wrong frame — RAID is single-machine; this is distributed erasure
   coding. Reuse an existing library, e.g. `reed-solomon-erasure`.)
5. **Payout is contingent on proof the provider still holds the bytes** — the escrow
   releases a provider's share only against a storage proof over time (see Open
   Questions — this mechanism is the hard part and is not yet chosen).

## Considered options

- **(A) Custodial central wallet (rejected):** viewrr holds funds + pays out +
  keeps spread = licensed money transmitter, KYC/AML, custody liability. Reverses
  `p2p-0020`/`p2p-0010`; not realistically buildable/operable by a solo dev.
- **(B) Non-custodial smart-contract escrow (chosen):** margin as a coded protocol
  fee; viewrr never touches funds. Storj/Filecoin-style.
- **Integrate an existing storage network instead of building one (open):** Storj/
  Sia/Filecoin/Arweave already solve pooled storage + proofs + settlement. Building
  our own proof-of-storage is research-grade. See Open Questions.

## Consequences

- **Non-custodial shield preserved:** viewrr stays a protocol; no MSB status from
  the storage product, same as `p2p-0020`.
- **Copyright posture improved** for the storage product (selling capacity, not
  content) — but generic storage invites **abuse** (illegal/unwanted content stored
  by buyers); needs an abuse/takedown stance consistent with `p2p-0010` de-index-only.
- **`p2p-0011` extended:** the pool is now not just media replication but a sellable,
  erasure-coded, third-party storage tier with proof-of-storage payouts.
- **Scope grows to a second product** — decentralized cloud storage — with its own
  durability SLA, support, and economics. Significant for a solo builder.

## Open Questions (unresolved — do NOT treat as decided)

1. **Proof-of-storage mechanism (the hard problem).** For the escrow to pay
   providers, it must verify a provider *still holds* its segments over time
   (Filecoin PoRep/PoSt-class proofs). This is genuinely hard. **Strong
   recommendation to evaluate integrating an existing network (Storj/Sia/Filecoin/
   Arweave) rather than building proof-of-spacetime from scratch.** Build-vs-integrate
   is the biggest open decision.
2. **Recurring rent vs one-time fee (economic flaw to resolve).** The example
   "$5 one-time for 15–20 GB" is **economically broken**: storage is an *ongoing*
   cost to the provider (bytes held indefinitely), so a one-time fee can't fund
   perpetual hosting. This must be **recurring rent** (per GB per month) or a
   **time-boxed lease**, with the escrow streaming payments over the storage period.
   Not yet decided.
3. **Pricing / bid mechanism.** How providers bid storage prices, how the buyer's
   request is matched to the cheapest bundle of segments, and how the ETA/quote is
   shown — analogous to `p2p-0020` Q3/Q5 but for storage. Not yet designed.
4. **Chain + contract platform** for the escrow (same L2 as `p2p-0020`? which
   contract standard?) and the proof-verification cost on-chain.
5. **Abuse/takedown** for buyer-stored arbitrary content, reconciled with
   `p2p-0010` (de-index-only, no backdoor) and encryption-at-rest.
