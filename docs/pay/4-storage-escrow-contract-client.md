# Storage escrow contract client (p2p-0021)

- **Issue:** viewrr/pay#4
- **Status:** Draft
- **Related ADRs:** p2p-0021 (governing), p2p-0022 (Go/Kotlin split, backstop), p2p-0023 (shared invariants), p2p-0020 (wallet/USDC/L2 lineage), p2p-0011 (RF≥2 pool), p2p-0010 (de-index-only)

## Problem & context

viewrr sells pooled mesh storage as a second monetization product (p2p-0021). A
buyer purchases N GB; the bytes are erasure-coded into small segments spread across
many provider devices; providers earn USDC for holding their segments over time.

The hard constraint is **non-custodial**: viewrr is a protocol, never a money
transmitter. It must never hold, route, or net user funds, and its margin must be a
**coded protocol fee** skimmed on-chain, not a custodial spread (p2p-0021 Decision 1;
p2p-0023 invariant 1). The rejected design routed payments through a central viewrr
wallet — that is money transmission (MSB/KYC/AML) and is explicitly out of bounds.

This issue builds **the Go client** that `viewrr-pay` uses to drive the escrow smart
contract: fund a deal, verify proof-of-storage, trigger auto-split payouts, and run
the contract-as-job-queue lease model for provider reassignment (p2p-0021 Decision 7).
It is build-order slice 4. The service is the only component that touches chains
(p2p-0022 Decision 3); the Kotlin Hub reaches it over gRPC (#1) and the wallet/chain
plumbing comes from #2. Proof-of-storage semantics that gate release come from #6.

The contract itself (Solidity/EVM source) is a separate deliverable and is **not** in
scope here — this issue is the off-chain Go client to it. Where the contract's own
economics are unresolved (recurring rent, chain platform, proof mechanism), this
client is built to a versioned ABI and treats those as injected parameters, not
hardcoded assumptions. See Open questions.

## Goals / Non-goals

**Goals**
- A Go package `internal/escrow` that wraps the storage escrow contract behind a
  clean interface: `FundDeal`, `DealStatus`, `SubmitStorageProof`, `TriggerPayout`,
  `ClaimReassignment`, `SubmitHandoffProof`, `ReleaseDefector`, plus event streaming.
- Deterministic, well-tested transaction construction and receipt/event decoding —
  this is money code (GETTING_STARTED invariant 5, conventions §6).
- Enforce the non-custodial invariant *structurally*: the client only ever submits
  transactions signed by the acting party's own wallet (#2); it never moves funds
  through a viewrr-owned account, never nets, never pays A-from-B.
- Surface deal lifecycle to the Hub via the gRPC surface defined in #1
  (`open-storage-deal`, `deal status`, etc.).
- Model the contract-as-job-queue timed-lease reassignment (Decision 7) as client
  calls that claim/renew/expire leases — coordination lives on-chain, workers stay
  stateless.
- Payout is **contingent on proof** (Decision 5): the client never signals release
  except against a verified storage proof from #6.

**Non-goals**
- Authoring the escrow smart contract itself (separate work; this client binds to its
  ABI).
- The proof-of-storage *mechanism* / proof generation (#6) — this client consumes a
  proof-verification verdict; it does not invent proof-of-spacetime.
- The Sia/Filecoin backstop and USDC↔SC/FIL swap (#5 / p2p-0022) — a distinct tier.
- Pricing/bid matching, ETA/quote UX (p2p-0021 OQ3) — not designed yet.
- Deciding rent-vs-one-time economics or the chain platform (issues #7, #DECISION,
  p2p-0021 OQ2/OQ4) — blockers, tracked in Open questions.
- Any custody, treasury float, or fund-netting logic — forbidden by invariant.

## Design

### Where it sits

```
Kotlin Hub ──gRPC(#1)──▶ viewrr-pay
                          ├─ internal/rpc      (gRPC handlers, #1)
                          ├─ internal/wallet   (seed→EVM key, eth client, #2)
                          ├─ internal/escrow   (THIS ISSUE)
                          └─ internal/proof     (#6 — proof verdicts)
                                   │
                          escrow ──┴──▶ Storage Escrow contract on L2 (Base)
```

`internal/escrow` depends on `internal/wallet` (#2) for signing + the eth client, on
`internal/proof` (#6) for release-gating verdicts, and is invoked by `internal/rpc`
(#1). It talks to exactly one chain endpoint through the wallet's client — no direct
node handling of its own.

### ABI binding approach

- Use the Go-native ethereum stack (`github.com/ethereum/go-ethereum`) — geth is
  Go-native, the reason Go was chosen (p2p-0022 rationale).
- Generate typed bindings from the contract ABI with `abigen` into
  `internal/escrow/abi/` (a `//go:generate` directive; the compiled ABI JSON is the
  source of truth, checked in and versioned). Do **not** hand-roll call encoding for
  money paths.
- Wrap the generated binding in a hand-written `Client` interface so the rest of the
  service depends on our interface, not on generated types, and so it is mockable in
  tests (no live chain needed for unit tests).
- The ABI is **versioned**: pin the deployed contract address + ABI version in config;
  refuse to operate against an unexpected code hash. This protects against binding to
  a contract that changed its payout math under us.

Sketch:

```go
package escrow

type DealID [32]byte

type Client interface {
    // Buyer funds a deal: locks buyer_paid into escrow, records the
    // provider set, segment commitments, term, and the coded protocol-fee bps.
    FundDeal(ctx context.Context, p FundParams) (DealID, *types.Receipt, error)

    DealStatus(ctx context.Context, id DealID) (Deal, error)

    // Provider (or a worker on its behalf) submits a storage proof for a term/epoch.
    // Verified by #6 off-chain and/or on-chain per OQ1/OQ4.
    SubmitStorageProof(ctx context.Context, id DealID, prov Address, proof Proof) (*types.Receipt, error)

    // Release the earned slice for proven providers for an epoch; the CONTRACT
    // performs the auto-split and skims the fee. Client only triggers.
    TriggerPayout(ctx context.Context, id DealID, epoch uint64) (*types.Receipt, error)

    // Contract-as-job-queue (Decision 7): claim a timed exclusive lease on a
    // reassignment job. One-winner-per-slot by construction.
    ClaimReassignment(ctx context.Context, id DealID, seg SegmentID) (Lease, *types.Receipt, error)
    SubmitHandoffProof(ctx context.Context, l Lease, proof Proof) (*types.Receipt, error)
    ReleaseDefector(ctx context.Context, id DealID, seg SegmentID) (*types.Receipt, error)

    // Event stream: DealFunded, ProofAccepted, PayoutReleased, LeaseClaimed,
    // LeaseExpired, HandoffProven, DefectorReleased, DealClosed.
    Subscribe(ctx context.Context, id DealID) (<-chan Event, error)
}
```

### Deposit / fund flow

1. Hub calls `open-storage-deal` (#1) with buyer, N GB, term, chosen provider bundle
   + segment commitments (from the pricing/matcher, OQ3 — passed in, not computed here).
2. `FundDeal` builds a transaction **signed by the buyer's wallet** (#2) that transfers
   `buyer_paid` USDC into the escrow contract (via ERC-20 `approve` + contract pull, or
   `permit` if supported) and records the deal parameters: provider set, per-provider
   payout schedule, `protocol_fee_bps`, `term`, `min_term`/`exit_bond` params.
3. The client asserts the contract invariant `buyer_paid > Σ(provider_payouts)` locally
   before sending (fail fast) — the surplus funds gas + viewrr's coded margin
   (Decision 2). The contract enforces it too; the client never relies solely on its
   own check.
4. Funds now sit in **on-chain escrow**. viewrr's account is never a hop. Return
   `DealID` to the Hub.

### Lock / hold

The escrow holds `buyer_paid` for the deal term. If pricing is **recurring rent /
time-boxed lease** (the likely resolution of OQ2 / #7), the deal is funded per period
or streamed, and payout is released per epoch against per-epoch proofs. The client is
written so the "one big lock then epoch releases" and "streamed per-period funding"
shapes are both expressible against the same ABI — the choice is a contract+config
parameter, not a client rewrite. Until OQ2 is decided we implement the epoch-release
plumbing and leave the funding cadence configurable (see Open questions).

### Release flow (proof-gated — the security-critical path)

Release is **pay-on-verified-delivery** (invariant 5) applied to storage: a provider's
share is released only against a storage proof it still holds its bytes (Decision 5).

1. Per epoch, providers (or workers) call `SubmitStorageProof`.
2. Proof verification (#6) yields a verdict. Two placements, decided by OQ1/OQ4:
   - **On-chain verification** — the contract itself checks the proof; `TriggerPayout`
     just advances the epoch and the contract splits. Highest trust, highest gas.
   - **Delegated/attested** — the proof is verified off-chain (or by the backstop
     network, #5/#6) and an attestation is submitted; the contract releases against the
     attestation. Cheaper, but introduces an attester **trust boundary** (see below).
   The client abstracts both behind `SubmitStorageProof` + `TriggerPayout`; which is
   live is a contract-capability flag.
3. On a verified epoch, `TriggerPayout` causes the **contract** to auto-split to
   providers and **skim the protocol fee**. The Go client never receives, holds, or
   forwards the funds — it only submits the trigger transaction and decodes the
   `PayoutReleased` event.
4. If proof is absent/invalid for an epoch, that provider's slice is **not** released
   (never pay-then-hope). Missed-proof handling (forfeit, grace, reassignment) follows
   the deal-migration path below.

### Dispute / defection / reassignment (Decision 6 & 7)

A provider may defect to a higher bid, or fail to prove and need replacing. Both use
the same **handoff-before-release with friction** machinery, and the escrow contract
**is** the SPOF-free job queue — no separate matching backend (Decision 7):

1. **Reassignment job = contract state.** When a segment needs a new home (defection
   or missed proof), the deal exposes a claimable job.
2. **Timed exclusive lease.** A worker calls `ClaimReassignment`; the contract grants a
   **timed exclusive lease** locked to that worker (one-winner-per-slot by
   construction). The blockchain is the coordination substrate; workers are stateless.
3. **Handoff first.** The replacement pulls the segment (recoverable via RF≥2 / erasure
   coding, p2p-0011) and calls `SubmitHandoffProof` **before** the defector is released.
   Durability never dips — the defector keeps serving until the replacement has proven
   storage.
4. **Lease TTL.** If the worker doesn't submit proof before the lease expires, the
   contract auto-releases the lease and another worker can claim (`LeaseExpired`). The
   client renews or abandons; it never assumes it still holds an expired lease.
5. **Economic gate & friction.** The swap is permitted only if
   `new_payout − exit_penalty − handoff_cost > old_payout` (defector funds its own
   replacement out of the upside), with a **min-term/lock-up + early-exit bond**
   (contract-slashable) damping churn. The client submits these as contract-enforced
   parameters; **it does not adjudicate** — the contract does. Exact numbers are
   unresolved (p2p-0021 OQ7 / #6).
6. `ReleaseDefector` is only valid *after* `HandoffProven`; the client enforces the
   ordering client-side and relies on the contract to enforce it authoritatively.

Note: if the backstop is Sia (`renterd` auto-repair, p2p-0022 / p2p-0021 OQ1/OQ6),
most of this migration machinery dissolves for the durable tier — `renterd` re-repairs
shards. The bespoke lease/matcher is primarily the **device hot tier** path. This
client implements the contract-as-queue calls; #6 decides how much of it is actually
exercised vs delegated.

### Trust boundaries & security notes

- **Non-custodial is structural, not conventional.** The client has no code path that
  transfers funds into or out of a viewrr-owned address. Every fund-moving transaction
  is signed by the party that owns the funds (buyer for funding, contract for payout).
  A code review gate: no viewrr key ever appears as `from` on a value transfer.
- **Release only against proof.** The single most dangerous bug would be releasing
  payout without a valid storage proof. Release is gated on a verified verdict (#6);
  unit + integration tests assert "no proof ⇒ no release" as a hard invariant.
- **Attester trust boundary (delegated verification path).** If proofs are verified
  off-chain and attested on-chain, whoever signs attestations can release funds —
  that key/role is the trust boundary and must be minimized, ideally the same
  decentralized network providing durability (#5/#6), not a viewrr-controlled oracle
  (a viewrr oracle that gates payouts drifts back toward custodial control). Flagged as
  an open question; do not ship the delegated path without resolving who attests.
- **Reorg / finality.** Deal funding and payout must wait for L2 finality before being
  reported as settled to the Hub; the client tracks confirmation depth and reports
  `pending`→`settled` transitions, never treating a single-block inclusion as final.
- **Idempotency.** Settlement gRPC calls (#1) must be idempotent: the client keys
  operations by `DealID`/epoch and refuses to double-fund or double-trigger (dedupe on
  submitted-tx-hash + on-chain state check). p2p-0022 OQ3 calls this out.
- **No secrets in logs.** slog structured logging; never log the private key, seed, or
  raw signed tx. (GETTING_STARTED invariant 4.)
- **Abuse/takedown & content-law** for buyer-stored arbitrary bytes (p2p-0021 OQ5,
  p2p-0010 de-index-only) are policy questions above this client, but the client must
  not build any backdoor to reach into or decrypt stored segments — it only settles.

### gRPC surface (from #1)

The client backs these Hub-facing methods (exact proto agreed in #1):
`OpenStorageDeal(buyer, gb, term, providerBundle) → DealID`,
`DealStatus(DealID) → {funded, epoch, providers[], proven[], released[], leases[]}`,
`SubmitStorageProof`, `TriggerPayout` (or contract-automatic), and the reassignment
calls. Streaming deal events over a server-stream mirrors `Subscribe`.

## Implementation plan

Ordered; each phase independently shippable per repo slice discipline.

**Dependencies:** hard-depends on **#1** (gRPC surface) and **#2** (wallet + eth
client). Coordinates with **#6** (proof verdicts) for the release gate. **Blocked** on
`#DECISION rent-vs-one-time` (#7 / OQ2) and the chain/contract-platform decision (OQ4)
for anything that hardcodes economics or the deployed ABI — build against a versioned
ABI + config to make progress before those land.

1. **ABI + binding scaffold.** Land a reference/mock escrow ABI, `abigen` bindings in
   `internal/escrow/abi/`, `//go:generate`, and the `Client` interface + a mock impl.
   Unblocks everything below without a deployed contract.
2. **Fund flow.** `FundDeal` + `DealStatus` against a local devnet (anvil/geth
   `--dev`). Assert `buyer_paid > Σ payouts` locally and on-chain. Buyer-signed only.
   Tests: correct escrow balance, fee bps encoded, reject under-funded deals.
3. **Proof-gated release.** `SubmitStorageProof` + `TriggerPayout` + `PayoutReleased`
   decoding, with #6's verdict as the gate. Invariant test: no proof ⇒ no release.
   Support both on-chain and attested verification behind the capability flag.
4. **Event streaming + Hub wiring.** `Subscribe`, wire into `internal/rpc` (#1) so the
   Hub can open deals and stream status. Finality/confirmation-depth handling +
   idempotency keys.
5. **Reassignment / contract-as-job-queue.** `ClaimReassignment`, lease TTL/renew,
   `SubmitHandoffProof`, `ReleaseDefector`; enforce handoff-before-release ordering and
   the `new − penalty − handoff > old` economic gate (params from config/#6). Gate
   behind a flag pending OQ1/OQ6 (may be no-op if Sia backstop handles repair).
6. **Config hardening.** Pin contract address + ABI/code-hash; refuse unexpected code;
   configurable funding cadence (lock vs stream) to absorb the OQ2 outcome.

## Open questions & risks

- **[BLOCKER] Recurring rent vs one-time (OQ2 / #7 / #DECISION).** One-time-fee-for-
  perpetual-storage is economically broken. Determines funding cadence and epoch/stream
  release shape. Client built configurable; final wiring waits on this.
- **[BLOCKER] Chain + contract platform (OQ4).** Which L2 (same as p2p-0020 / Base?),
  which contract standard, and on-chain proof-verification cost. The deployed ABI and
  the on-chain-vs-attested proof placement both hinge on this.
- **Proof-of-storage mechanism (OQ1 / #6).** On-chain proof verification vs delegated
  attestation is unresolved; strong steer to integrate an existing network (Sia
  `renterd`) rather than build proof-of-spacetime. Changes how much of Decision 6/7 the
  client actually exercises (OQ6).
- **Attester trust boundary.** If verification is delegated/attested, who holds the
  attesting key? A viewrr-controlled oracle re-introduces custodial-flavored control
  over payouts — must be avoided; needs a decision before the delegated path ships.
- **Deal-migration parameters (OQ7).** Min-term/lock-up length, exit-bond size,
  `handoff_cost` measurement, lease TTL — mechanism decided, numbers not. Client takes
  them as config.
- **Abuse/takedown & content-law (OQ5, p2p-0021/p2p-0020).** Legal review required
  before any money movement ships (#9). Not this client's logic, but gates release.
- **Gas/fee economics.** The coded protocol fee must cover gas + margin across L2 fee
  volatility; if gas spikes past the fee surplus, payouts could underfund. Needs a fee
  model check once the chain is chosen.

## Verification

- **Unit tests (no chain):** transaction construction, ABI encode/decode, the
  `buyer_paid > Σ payouts` assertion, handoff-before-release ordering, lease
  expiry/renew logic, event decoding. Mock `Client`.
- **Integration tests (local devnet):** deploy the reference escrow to anvil/geth
  `--dev`; run full lifecycle — fund → prove → payout → verify on-chain split + fee
  skim; missed-proof → no release; defection → claim lease → handoff proof → release
  defector. Assert escrow and provider balances at each step.
- **Invariant tests (must pass, money-critical):**
  1. No viewrr-owned address ever appears as `from` on a value transfer (non-custodial).
  2. No storage proof ⇒ no payout release (pay-on-verified-delivery).
  3. `buyer_paid > Σ(provider_payouts)` holds for every funded deal.
  4. Defector never released before replacement's handoff proof accepted.
  5. Reassignment lease is exclusive: two workers cannot both hold a live lease on the
     same segment slot.
- **Idempotency test:** replaying the same fund/trigger gRPC call does not double-spend
  or double-release.
- **Finality test:** deal reported `settled` only after configured confirmation depth;
  simulated reorg does not surface a reverted payout as settled.
- **No-secrets check:** log scan asserts no key/seed/signed-tx material is emitted.
- **Gate:** `make build && make test && make lint` green; every new money path has a
  test (conventions §6). Do not ship the delegated-attestation path or hardcode
  economics until the two blockers above are resolved.
