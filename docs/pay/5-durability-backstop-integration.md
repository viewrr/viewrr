# Sia/Filecoin durability backstop integration

- **Issue:** viewrr/pay#5
- **Status:** Draft
- **Related ADRs:** p2p-0022 (governing), p2p-0021 (storage escrow), p2p-0023 (monetization overview / shared invariants), p2p-0011 (storage pool + encrypted backup tier)

## Problem & context

viewrr's storage supply is **hybrid** (p2p-0022, Decision 1). The **hot tier** is
contributed member devices — phones and desktops contributing 2–3 GB each: cheap,
best-effort, erasure-coded, RF≥2 (p2p-0011), but structurally churny. Consumer
devices cannot provide the one thing a storage marketplace must eventually promise:
*guaranteed* durability for data that has to survive mobile churn.

p2p-0022 resolves this with a **durable backstop** — a decentralized storage network
(Sia or Filecoin; decided in #8) rented *under* the device mesh. It is the p2p-0011
"encrypted backup tier": viewrr rents guaranteed durability from the network for the
data the hot tier can't be trusted to hold.

Two hard constraints from the ADR shape the whole integration:

1. **The backstop settles in the network's native token (SC/FIL), never USDC.** Users
   and contributors are paid in USDC exactly as p2p-0020/p2p-0021 specify. The backstop
   leg is **viewrr paying an infrastructure vendor for durability**, funded from viewrr's
   protocol-fee **margin** (like an AWS bill), with an internal USDC→SC/FIL swap. Users
   never see it (p2p-0022 Decision 2; p2p-0023 invariant 3).
2. **This is not custody.** The swap is viewrr's own treasury paying a supplier — not
   holding, routing, or netting user funds. Preserving the non-custodial shield
   (p2p-0020 Decision 1, p2p-0010) is non-negotiable.

This is **build-order slice 5** in `GETTING_STARTED.md`. The settlement service is the
only component that talks to chains and storage networks; the mesh data plane stays in
Kotlin/worklet.

## Goals / Non-goals

**Goals**

- A **pluggable backstop abstraction** (`internal/backstop`) over the specific durable
  network, so the network choice (#8) swaps one concrete implementation without touching
  callers.
- Form, monitor, and renew **durable storage deals** for backstopped (encrypted,
  erasure-coded) segments, preferring the network's **own auto-repair** over anything
  custom (Sia `renterd` auto-repairs; p2p-0022 OQ1, issue body).
- An internal **USDC↔SC/FIL swap + margin float** (treasury operation), abstracted from
  users and from the rest of the service.
- **Cost accounting**: attribute per-deal token spend to the storage deals it backstops
  and reconcile it against the protocol-fee margin skimmed by escrow #4 — so the margin
  provably covers backstop cost and no user is ever charged in SC/FIL.
- Enforce the **encryption-before-store** boundary: the backstop only ever sees opaque
  encrypted slices (consistent with p2p-0010 de-index-only / no backdoor).

**Non-goals**

- The **device hot-tier data plane** — segment serving, device pool, hot-tier replication
  (Kotlin/worklet, `#127`, p2p-0011). Not here.
- **Building proof-of-spacetime** or any custom durability proof — the backstop network
  provides its own (p2p-0023 invariant 5, "integrate over build").
- The **custom deal-migration matcher** for the device tier (#6). The backstop relies on
  network-native repair, which *dissolves* most Decision-6/7 machinery for this tier
  (p2p-0021 OQ1/OQ6).
- **Choosing the network** (Sia vs Filecoin vs Storj) or the swap mechanism — that is #8.
  This design treats it as pluggable behind an interface.
- **Surfacing SC/FIL** to any user or contributor. Ever.

## Design

### 1. The backstop abstraction (network-pluggable)

A single Go package `internal/backstop` defines a network-agnostic interface; concrete
implementations wrap the Go-native clients (`renterd` for Sia, Lotus for Filecoin). The
network is selected by config; **#8 only decides which implementation is wired**, nothing
above the interface changes.

```go
// internal/backstop
type Backstop interface {
    // Store an already-encrypted, erasure-coded object; returns a deal handle.
    Store(ctx context.Context, obj EncryptedObject, term StorageTerm) (Deal, error)
    // Retrieve by handle (recovery / audit).
    Retrieve(ctx context.Context, h DealHandle) (EncryptedObject, error)
    // Durability/health of a deal; whether the network is repairing it.
    Health(ctx context.Context, h DealHandle) (DealHealth, error)
    // Extend an active deal before expiry (recurring rent — see #7).
    Renew(ctx context.Context, h DealHandle, term StorageTerm) (Deal, error)
    // Priced quote in the network token for a term — feeds cost accounting.
    Quote(ctx context.Context, size int64, term StorageTerm) (TokenCost, error)
}

type Provider string // "sia" | "filecoin"  (decided in #8)
```

- **Sia impl** (`internal/backstop/sia`): talks to `renterd` over its HTTP API. `renterd`
  handles host selection, contract formation, and **auto-repair/migration** of shards when
  hosts drop — so `Health` largely *reports* repair the network is already doing.
- **Filecoin impl** (`internal/backstop/filecoin`): talks to Lotus. Filecoin has proofs
  but **no native auto-repair**, so this impl needs a thin repair layer (re-seal / re-deal
  on fault) — a real cost difference the #8 decision must weigh.
- A factory (`backstop.New(cfg)`) returns the configured `Backstop`. Callers depend only
  on the interface.

### 2. When the backstop is invoked (hybrid placement)

The backstop is **mostly invoked by the settlement service itself, not by an end user**.
Two triggers:

- **Push-on-ingest (encrypted backup tier):** data classified as durability-critical is
  written to the backstop at store time, in parallel with the hot tier. This is the
  p2p-0011 backup tier.
- **Repair-on-degrade:** when hot-tier replication for a segment drops below its RF floor
  (signal comes from the Hub over gRPC), the segment is pushed to the backstop so
  durability never depends solely on churny devices.

*What* data goes to the backstop vs stays hot-only (the tier **placement policy**) is
explicitly a p2p-0022 open question (OQ2) — see Open questions. This design gives the
mechanism; the policy is a pluggable predicate `ShouldBackstop(segmentMeta) bool` so the
policy can land without reworking the integration.

### 3. Encryption & data boundary

Objects are **encrypted and erasure-coded before** they reach `Backstop.Store` — the
backstop network stores opaque slices only. viewrr cannot read backstopped content
(preserves p2p-0010 no-backdoor / encryption-at-rest). The erasure coding reuses the same
Reed-Solomon library as the hot tier (p2p-0021 Decision 4); the backstop stores the
already-coded shards, and additionally benefits from the network's own internal
redundancy.

### 4. Cost accounting into escrow (#4)

The user's money path is unchanged: a buyer pays **USDC into the escrow contract**, which
auto-splits to hot-tier providers and **skims viewrr's margin as a coded protocol fee**
(#4 / p2p-0021 Decisions 1–2). The backstop cost is paid **out of that margin**, in the
service's own treasury, off-chain — it never touches the user leg.

Accounting flow:

1. `Backstop.Quote` gives the token cost of backstopping a segment for a term.
2. That cost is **attributed** to the storage deal(s) it backstops and recorded in the
   settlement service's ledger (per-deal, per-term).
3. A **margin reconciliation** check asserts, per deal / per period, that
   `protocol_fee_margin_collected (USDC) ≥ backstop_cost (USDC-equivalent) + gas + target
   margin`. If a deal's backstop cost would exceed its margin, that is a **pricing signal
   fed back to #4/#7** (recurring-rent economics), not a user charge.

So the escrow contract stays the single source of truth for *user* funds; the backstop
ledger is a private treasury cost book that must be *covered by* the skimmed margin.
viewrr never adds an SC/FIL line to anything a user sees.

### 5. USDC↔SC/FIL swap + treasury float

A `Treasury` abstraction (`internal/backstop/treasury`, also pluggable per #8) holds a
small **viewrr-owned float** of the network token and tops it up by swapping USDC margin:

```go
type Treasury interface {
    Balance(ctx) (TokenAmount, error)          // current float
    EnsureFunded(ctx, need TokenAmount) error   // swap USDC→token if float low
}
```

- Backstop deals draw from the float; when it drops below a low-water mark, `EnsureFunded`
  swaps USDC (from margin) to token via a DEX or a manual/OTC top-up, **capped** per
  interval to bound slippage/volatility exposure.
- The float is sized to smooth token-price volatility so day-to-day deal formation doesn't
  swap at every deal. Float sizing / swap venue is part of #8 OQ1.
- This is a **treasury operation on viewrr's own funds** — the code must have **no path**
  that funds a backstop deal directly from a user's inbound USDC (that would be
  pass-through custody). The swap source is *always* already-skimmed margin.

### 6. gRPC surface & idempotency

The Hub calls the settlement service for placement decisions (e.g. `RequestBackstop`,
folded into the `open-storage-deal` / storage RPCs of slice 1). Backstop *deal formation*
is internal to the Go service. All settlement RPCs are **idempotent** — a retried
`RequestBackstop` for the same segment/term must not form a duplicate paid deal (p2p-0022
OQ3). Deals are keyed by a deterministic `(segmentID, term)` handle.

## Implementation plan

Phases are independently shippable; each money/treasury path gets tests
(`GETTING_STARTED` §6).

- **Phase 0 — Interface + stubs (no dependency; can start now).** Define `Backstop`,
  `Treasury`, `Deal`, `TokenCost` types, config surface, and a `fake` implementation for
  tests. Wire the placement predicate `ShouldBackstop` as a stub. Unblocks everything else
  without waiting on #8.
- **Phase 1 — Concrete client (gated on #8).** Implement the chosen network's client
  (`renterd` or Lotus) behind `Backstop`. If Filecoin, include the thin repair layer.
- **Phase 2 — Deal lifecycle.** `Store` / `Retrieve` / `Health` / `Renew` against the
  network devnet/testnet. Rely on network auto-repair (Sia) or the repair layer (Filecoin);
  monitor via `Health`. Renewal ties to the recurring-rent decision (#7).
- **Phase 3 — Treasury float + swap.** Implement `Treasury.EnsureFunded` (capped
  USDC→token swap) and float low-water management. Depends on the wallet/chain client
  (#2, slice 2) for the USDC side.
- **Phase 4 — Cost accounting into escrow (#4).** Per-deal token ledger + margin
  reconciliation against the protocol fee skimmed by the escrow client. **Hard dependency
  on #4.**
- **Phase 5 — Placement policy + Hub wiring.** Land the real `ShouldBackstop` policy
  (p2p-0022 OQ2), the push-on-ingest and repair-on-degrade triggers, and the gRPC
  `RequestBackstop` path (idempotent). Depends on slice 1 (gRPC contract).
- **Phase 6 — Repair reliance / health.** Observe and alert on network repair; on Filecoin,
  exercise the re-deal path. Coordinate scope with #6 (the device-tier matcher) — the
  backstop tier should need little of it.

**Dependencies:** #4 (escrow — margin source for accounting/reconciliation), #2 (wallet/
chain client — USDC side of the swap), #8 (network + swap decision — gates Phases 1/3),
loosely #6 (matcher — backstop mostly avoids it via auto-repair), #7 (recurring-rent —
sets renewal/term economics). Legal (#9) gates any real money movement.

## Open questions & risks

- **#8 — which backstop network + swap mechanism (p2p-0022 OQ1).** Gates the concrete
  client and the treasury swap. Sia (`renterd`) buys auto-repair and shrinks #6; Filecoin
  needs a repair layer. Treated here as pluggable.
- **Tier placement policy (p2p-0022 OQ2).** What data is durability-critical enough to pay
  to backstop vs kept hot-only, and the cost/durability trade per tier. Mechanism is here;
  the policy predicate is deferred.
- **Recurring-rent economics (#7 / p2p-0021 OQ2).** A one-time fee can't fund perpetual
  backstop rent; deal renewal (Phase 2) needs streamed/time-boxed funding. Backstop cost is
  a direct input to this pricing.
- **Float / volatility risk.** SC/FIL price swings, DEX liquidity, and slippage make the
  float a treasury-risk surface. Cap swaps, size the float, monitor. Not user-facing but
  it is viewrr's own money.
- **Treasury key custody.** The float wallet holds viewrr's (not user) funds, but its keys
  are still secrets — gitignored, never logged (`GETTING_STARTED` invariant 4).
- **Non-custodial line.** The swap must draw only from already-skimmed margin; any code
  path funding a backstop deal from inbound user USDC would be pass-through custody and is
  forbidden. Enforce and test.
- **Legal (#9).** Get counsel before shipping any money movement, including the vendor swap.

## Verification

- **Unit** — `Backstop` and `Treasury` exercised via the `fake` impl: deal form/renew/
  health, idempotent handles, capped-swap logic, margin-reconciliation math.
- **Integration** — real `renterd`/Lotus on devnet/testnet: `Store`→`Retrieve` round-trip,
  observe network auto-repair (or the Filecoin repair layer) after simulated host loss,
  renewal before expiry.
- **Invariant tests (money-critical):**
  - No code path funds a backstop deal from inbound user USDC (non-custodial).
  - No user-facing surface (gRPC responses, logs, receipts) ever emits SC/FIL — only USDC.
  - Objects handed to `Backstop.Store` are already encrypted (backstop sees opaque bytes).
  - Per-deal reconciliation asserts skimmed margin ≥ backstop cost + gas + target margin,
    else raises a pricing signal (never a user charge).
- **Idempotency** — retried `RequestBackstop`/`Store` for the same `(segmentID, term)` forms
  no duplicate paid deal.
- **Secrets** — treasury keys never logged; `.env`/wallet files gitignored (grep/CI check).
