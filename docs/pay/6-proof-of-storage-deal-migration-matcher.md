# Proof-of-storage + deal-migration matcher

- **Issue:** viewrr/pay#6
- **Status:** Draft
- **Related ADRs:** [p2p-0021](../adr/p2p-0021-storage-marketplace-smart-contract-escrow.md) (Decisions 5/6/7 — proof-gated payout, handoff-before-release, contract-as-job-queue), [p2p-0022](../adr/p2p-0022-storage-supply-hybrid-and-go-settlement-service.md) (hybrid supply, Go settlement service, integrate-not-build), [p2p-0023](../adr/p2p-0023-monetization-suite-overview.md) (shared invariants), [p2p-0011](../adr/p2p-0011-multi-device-storage-pool.md) (RF≥2, erasure coding), [p2p-0010](../adr/p2p-0010-operator-power-deindex-only-no-backdoor.md) (de-index-only)

## Problem & context

The storage marketplace (`p2p-0021`) only works if the escrow can **pay a provider
only against proof it still holds the bytes** — invariant #5 in `GETTING_STARTED.md`:
pay-on-verified-delivery, never pay-then-hope. Two hard mechanisms follow from that:

1. **Proof-of-storage** — a repeated, cheap-to-verify challenge showing a provider
   still holds a specific segment at time _T_ (`p2p-0021` Decision 5). This is the
   signal the escrow (#4) uses to release each rent installment, and its **absence**
   is what marks a deal as needing repair.
2. **Deal-migration matcher** — when a provider drops (fails proof, goes offline, or
   defects to a higher bid), a replacement must pull the segment (recoverable via
   RF≥2 / erasure coding, `p2p-0011`) and **prove storage before the defector is
   released** (`p2p-0021` Decision 6, handoff-before-release), coordinated through the
   escrow contract acting as a SPOF-free job queue with timed leases (Decision 7).

Both ADRs land emphatically on **integrate, don't build**: proof-of-spacetime is
research-grade, and Sia's `renterd` already auto-repairs and migrates a dataset's
shards when hosts drop (`p2p-0021` OQ1/OQ6, `p2p-0022` OQ, `p2p-0023` invariant #5).
So this issue is primarily about **where verification lives and how it gates money**,
not about inventing a novel proof scheme. The bespoke matcher is only fully needed on
the "build-our-own device-mesh hot tier" path; on the backstop tier it collapses into
delegating to the durable network's own repair.

This is build-order slice 6 (the last), sitting on top of the escrow client (#4) and
the backstop integration (#5). It is the code path that turns a static escrow into a
self-healing, proof-gated storage deal.

## Goals / Non-goals

**Goals**
- Define a proof-of-storage **verification interface** in the Go settlement service
  that is agnostic to the underlying tier (device hot tier vs Sia/Filecoin backstop).
- Specify how a verified/failed proof **gates escrow release** (#4) and **triggers the
  backstop** (#5) — the two integration seams named in this issue.
- Specify the **deal-migration matcher**: detect a dropped provider, open a
  reassignment job on the contract-as-queue, drive handoff-before-release, release the
  defector only after the replacement proves storage.
- Keep the whole path **non-custodial** — the contract moves funds; this service only
  submits verified proofs and reassignment transactions.
- Bias to **delegation**: for the backstop tier, prove-and-repair is the network's job
  (`renterd`); the service verifies at the tier boundary, not per-shard.

**Non-goals**
- Building a novel proof-of-spacetime / PoRep scheme (`p2p-0021` OQ1 — explicitly a
  build-vs-integrate decision, defaulting to integrate).
- Choosing the backstop network or the USDC↔token swap (that is #5 / `p2p-0022` OQ1).
- Setting deal-migration **parameters** — min-term, exit-bond size, lease TTL,
  handoff-cost accounting (`p2p-0021` OQ7 — mechanism decided, numbers not).
- The escrow contract itself, deal funding, payout split, protocol-fee skim (that is
  #4 / `p2p-0021` Decisions 1–4).
- Any mesh data-plane logic (segment serving, replication) — stays in Kotlin/worklet.
- Abuse/takedown policy for buyer-stored content (`p2p-0021` OQ5).

## Design

### Placement in the service

New `internal/proof` package (verification) and `internal/migration` package
(matcher), both consumed by the `internal/escrow` client (#4) and calling into
`internal/backstop` (#5). The Hub reaches everything via gRPC (`internal/rpc`); the
Hub never talks to a chain or storage network directly (`p2p-0022` boundary).

```
Hub ──gRPC──▶ rpc ──▶ escrow (deal state, contract-as-queue)
                        │  ├─▶ proof      (verify a provider still holds bytes)
                        │  └─▶ migration  (reassign on drop; handoff-before-release)
                        └─▶ backstop      (delegate durability + native repair)
```

### 1. Proof-of-storage verification

Model a proof as a tier-agnostic interface so the two supply tiers plug in behind one
seam:

```go
// internal/proof
type Verifier interface {
    // Challenge produces a nonce/challenge for a (deal, segment, provider) at time T.
    Challenge(ctx, dealID, segmentID, provider) (Challenge, error)
    // Verify checks a provider's response proves possession at T.
    Verify(ctx, Challenge, Response) (Result, error)
}

type Result struct {
    Held      bool
    ProvenAt  time.Time
    SegmentID SegmentID
    Provider  Provider
}
```

Two implementations, matching the hybrid supply model (`p2p-0022`):

- **Backstop tier (default, integrate-first):** `SiaVerifier` / `FilecoinVerifier`
  delegates to the network. Sia `renterd` continuously proves and auto-repairs its
  contracts; the service **verifies at the tier boundary** — query `renterd` for
  contract/health status of the dataset and treat the network's own PoSt as the proof.
  Filecoin exposes PoRep/PoSt but no native auto-repair, so its verifier reports health
  but the matcher (below) must drive repair. This keeps us on the "integrate" path
  (`p2p-0021` OQ1, `p2p-0023` #5) and dissolves most bespoke proof machinery.
- **Device hot tier (build-our-own, only if a custom mesh path is chosen):** a
  lightweight challenge–response over a segment (e.g. Merkle-root + random-index
  challenge, or a compact PDP-style check) proving the device still holds the
  erasure-coded shard. This is the research-grade part; it is gated behind the
  build-vs-integrate decision (`p2p-0021` OQ1/OQ6) and is **not** built unless that
  decision selects the custom mesh. Interface is defined now; implementation deferred.

**Verification cadence** is driven by the escrow's rent schedule (recurring rent, not
one-time — `p2p-0021` OQ2): each installment window, the service issues challenges to
the deal's providers and records `Result`s. On-chain proof-verification cost is an open
economic input (`p2p-0021` OQ4) — the design keeps proofs **off-chain-computed,
on-chain-attested** (the service submits a signed verified-proof attestation the
contract accepts to release the installment), rather than verifying full proofs in the
EVM.

### 2. How proofs gate escrow release (#4)

The escrow (`p2p-0021` Decision 5) releases a provider's share **only against a
storage proof over time**. Concretely, per installment:

1. `escrow` asks `proof.Verify` for each (segment, provider) in the deal.
2. On `Held == true`: the service submits a **release/attestation transaction** to the
   escrow contract authorizing that installment's payout; the contract splits to
   providers and skims viewrr's protocol fee (`buyer_paid > Σ payouts`, Decision 2).
   The service **never touches the funds** (invariant #1) — it only attests and the
   chain pays.
3. On `Held == false` (or timeout / no response): **no release** for that provider, and
   the deal is flagged for migration (below). The buyer's escrowed funds stay locked;
   nothing is paid for bytes not proven held.

This is the direct enforcement of pay-on-verified-delivery for storage.

### 3. Deal-migration matcher (provider drops → reassign)

Follows `p2p-0021` Decisions 6 & 7 exactly. The **escrow contract is the job queue and
lock** — no separate matching backend, no bespoke distributed queue.

1. **Detect drop.** A failed/absent proof (step 2.3), an explicit defection bid, or a
   liveness miss marks a segment's provider slot as needing repair.
2. **Open a reassignment job = contract state.** The service writes the job into escrow
   contract state (the segment to be re-hosted, its recovery info, the payout on offer).
3. **Claim via timed exclusive lease.** Any stateless worker (a replacement-provider
   client) submits a **claim transaction**, granting a one-winner timed lease (locked to
   that worker). The blockchain gives one-winner-per-slot by construction (Decision 7);
   if the lease TTL expires without proof-of-handoff, it auto-releases and another
   worker can claim. No consensus system is built.
4. **Handoff before release (durability never dips).** The **defector keeps serving**
   until handoff completes. The replacement pulls the segment — recoverable from RF≥2 /
   erasure-coded peers (`p2p-0011`) — then runs the **same `proof.Verify`** as a fresh
   provider. Only when the replacement's proof passes does the contract release the
   defector and start paying the replacement.
5. **Economic guardrails (Decision 6).** A defection swap is admitted only if
   `new_payout − exit_penalty − handoff_cost > old_payout`, so a defector funds its own
   replacement out of the upside; a min-term / lock-up + slashable early-exit bond damps
   thrash. The **mechanism** is implemented here; the **parameters** are `p2p-0021` OQ7
   (open) and are read from deal config, not hardcoded.

**Backstop-tier shortcut:** if a dropped segment lives on the Sia backstop, migration is
**delegated** — `renterd` auto-repairs/migrates shards; the service just re-verifies at
the tier boundary and needs no custom matcher run. The bespoke matcher path above is
exercised for the device hot tier and for Filecoin (no native auto-repair). This is the
"integrate dissolves most of Decision-6/7 machinery" outcome (`p2p-0021` OQ6).

### 4. How proofs trigger the backstop (#5)

The backstop is the durability floor for data the churny device tier can't guarantee
(`p2p-0022`). A proof failure escalates through the tier placement policy:

- If a hot-tier segment repeatedly fails proof **and** the matcher can't find a
  replacement within the mesh (insufficient healthy RF), the service **pushes that
  segment to the durable backstop** via `internal/backstop` (#5): open/extend a
  Sia/Filecoin deal, funded by the USDC↔SC/FIL swap from viewrr's **margin float** — a
  vendor payment, never surfaced to users (`p2p-0022` Decision 2, invariant #3).
- The backstop copy then becomes the recovery source for future hot-tier repairs.

The exact **tier placement policy** (what escalates when) is `p2p-0022` OQ2 (open); the
design exposes the trigger hook and leaves the threshold as config.

## Implementation plan

Ordered; hard-blocked on #4 (escrow) and #5 (backstop), and on two design decisions.

1. **Define the `proof.Verifier` interface + `Result` types** (`internal/proof`).
   Pure, tested, tier-agnostic. No network calls yet. _(Unblocked — can start now.)_
2. **Backstop verifier (`SiaVerifier`)** — query `renterd` for contract/dataset health
   and map to `Result`. _Depends on #5 (backstop client) and the OQ1 network choice._
3. **Wire proof-gated release into `escrow`** — per-installment verify → attest/release
   on success, withhold + flag on failure. _Depends on #4._ This alone delivers
   pay-on-verified-delivery for storage even before the matcher exists.
4. **Contract-as-queue reassignment job model** — write/read reassignment jobs and
   timed leases in escrow state; claim transaction + TTL expiry. _Depends on #4
   (contract-as-job-queue lease model, Decision 7)._
5. **Migration matcher orchestration (`internal/migration`)** — detect drop → open job
   → drive handoff-before-release → re-verify replacement → release defector. Economic
   guardrail check reading parameters from deal config. _Depends on 3 + 4._
6. **Backstop escalation hook** — on unrepairable hot-tier failure, push segment to the
   durable tier via #5. _Depends on 5 + #5._
7. **Device-tier `proof` implementation (custom challenge–response)** — **only if** the
   build-vs-integrate decision (`p2p-0021` OQ1/OQ6) selects the custom mesh path.
   Otherwise this slice is dropped and the device tier leans on the backstop for
   guaranteed durability. _Blocked on decision._
8. **gRPC surface** for deal health / migration status the Hub can observe
   (`internal/rpc`). _Depends on 3–6._

**Decision gates that block this issue** (do not guess — `GETTING_STARTED.md` §5):
build-vs-integrate proof-of-storage (OQ1) → gates step 7; backstop network choice
(OQ1/#5) → gates steps 2/6; recurring-rent-vs-one-time (OQ2) → gates the installment
cadence in step 3; chain/contract platform + on-chain proof cost (OQ4) → gates the
attestation format in step 3.

## Open questions & risks

- **Build vs integrate proof-of-storage (`p2p-0021` OQ1, the big one).** Default:
  integrate (Sia). If the device hot tier must self-prove without a backstop copy, a
  custom scheme (step 7) is research-grade and risky for a solo dev. Recommendation:
  do **not** build it; rely on the backstop for guaranteed durability and keep the hot
  tier best-effort (consistent with `p2p-0011` RF≥2 for availability, backstop for
  durability).
- **Is the bespoke matcher even needed (`p2p-0021` OQ6)?** If everything durable lives
  on Sia, `renterd` auto-repair removes most of steps 4–5. The matcher is real work
  only for a custom mesh or Filecoin. Decide the tier model before building it.
- **Deal-migration parameters (`p2p-0021` OQ7).** Min-term, exit-bond size, lease TTL,
  how `handoff_cost` is measured/charged — all unresolved. Implemented as config, not
  constants, so they can be tuned post-decision.
- **On-chain proof-verification cost (`p2p-0021` OQ4).** Verifying full storage proofs
  in the EVM may be prohibitively expensive; the off-chain-verify / on-chain-attest
  split assumes the service is a trusted attester. Is that acceptable, or does it
  reintroduce a trust point the non-custodial design tried to remove? Needs a threat
  model (who can forge an attestation, and does the contract need a challenge/dispute
  window?).
- **Recurring rent vs one-time (`p2p-0021` OQ2).** The proof cadence is meaningless
  without the streaming-rent model; the naive one-time fee is economically broken.
- **Tier placement policy (`p2p-0022` OQ2).** What escalates to the backstop and when —
  drives step 6's thresholds.
- **Sybil / self-dealing on reassignment.** A provider could defect and "re-win" its own
  segment under a fresh identity to farm the payout; the exit-penalty/handoff-cost math
  and pseudonymity model (`p2p-0006`/`p2p-0008`) need checking against this.
- **Abuse/takedown (`p2p-0021` OQ5).** Migration must honour de-index-only
  (`p2p-0010`) — reassigning a de-indexed segment's repair is a policy question.

## Verification

- **Unit tests (money-critical, per `GETTING_STARTED.md` §6):** `proof.Verify` returns
  the correct `Result` for held / not-held / malformed / timed-out responses;
  proof-gated release **never** authorizes a payout when `Held == false`; the economic
  guardrail rejects a swap unless `new_payout − exit_penalty − handoff_cost > old_payout`.
- **Migration invariant test — durability never dips:** simulate a defection and assert
  the defector is released **only after** the replacement's proof passes
  (handoff-before-release, Decision 6). Assert one-winner-per-lease under concurrent
  claims (Decision 7) and that lease-TTL expiry re-opens the job.
- **Non-custodial assertion:** trace every payout path and prove the service only
  submits attestation/reassignment transactions — it never holds, routes, or nets funds
  (invariant #1). A test that fails if any code path moves USDC through a
  service-controlled account.
- **Backstop delegation test (`SiaVerifier`):** against a `renterd` test instance, drop
  a host and assert the network auto-repairs and the boundary verifier reflects recovered
  health — confirming no custom matcher run is needed on that tier.
- **Escalation test:** repeated hot-tier proof failure with no healthy replacement
  triggers exactly one backstop push (#5), funded from margin float, never surfaced as a
  user-facing charge (invariant #3).
- **gRPC contract test:** deal-health / migration-status calls are idempotent
  (`p2p-0022` OQ3) — replaying a settlement/reassignment call does not double-release.
