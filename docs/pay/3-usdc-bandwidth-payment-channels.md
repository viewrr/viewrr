# USDC bandwidth payment channels

- **Issue:** viewrr/pay#3
- **Status:** Draft
- **Related ADRs:** p2p-0020 (governing), p2p-0023 (suite invariants), p2p-0022 (Go/Kotlin split), p2p-0010 (no broker), p2p-0006/p2p-0008 (pseudonymity), viewrr#142 (BIP39 seed), viewrr#160 (fair-share throttle)

## Problem & context

Seeding costs the seeder real bandwidth. p2p-0020 defines the phase-2 layer that lets a
downloader **optionally** pay a seeder for priority bandwidth, turning a would-be
free-rider into a revenue source — without breaking the free tier, pseudonymity, or the
"protocol, not money transmitter" legal shield.

This is the first revenue path and the core money-movement path in `viewrr-pay`. It is
build-order slice 3, and it depends on slice 1 (gRPC contract, #1) and slice 2
(seed-derived EVM wallet + L2 chain client, #2).

The hard constraints inherited from p2p-0020 / p2p-0023, which this design must not
contradict:

1. **Non-custodial, always.** viewrr never holds, routes, nets, or brokers funds. Funds
   sit in the user's own wallet or in an on-chain channel contract — never in this
   service. No in-app fiat off-ramp.
2. **Opt-in / free tier intact.** No wallet unless the user opts in; the free multi-source
   swarm works with this whole service absent.
3. **USDC on an EVM L2 (Base).** Per-segment micropayments ride an off-chain payment
   channel; gas is paid only at channel open/close, amortized by reuse.
4. **Peer-settled, never platform-brokered.** A paying pair reveals its two wallet
   addresses to each other only; the server learns nothing and keeps no ledger.
5. **Pay-on-verified-delivery.** A segment's micropayment is released only after that
   segment's hash verifies. Downloader sets a max-spend cap and can bail at any segment
   boundary.
6. **Proportional QoS, granted locally.** The seeder's own `PeerPolicyEngine` (viewrr#160)
   raises its throttle in proportion to the payment rate it sees — no server directive.

## Goals / Non-goals

**Goals**

- A pairwise, unidirectional USDC payment channel (payer = downloader, payee = seeder) on
  Base: open/reuse, off-chain per-segment vouchers, cooperative and uncooperative
  close/settle.
- Per-segment pay-on-verified-delivery with a hard, contract-enforced max-spend cap.
- The gRPC surface (from #1) the Kotlin Hub calls to drive the channel and to feed the
  payment signal into its local throttle.
- Money-critical logic (voucher build/sign/verify, cap accounting) as pure, exhaustively
  tested Go.
- Double-spend / replay resistance anchored on-chain and in EIP-712 signing.

**Non-goals**

- Payment **routing** / paid multi-source swarm — deferred by p2p-0020 (single-source paid
  pull only). Paid pulls select the single cheapest seeder and use **one** channel.
- Segment **hash verification** and segment serving — that is the Kotlin/worklet data
  plane, not this service. This service consumes the Hub's "verified" signal.
- Throttle / QoS enforcement and live ETA computation — the seeder's `PeerPolicyEngine`
  and `FairShareManager`/`TokenBucketThrottle` (viewrr#160) own that. This service only
  emits the payment-rate signal.
- The DHT price-quote lookup (Hub/mesh concern). This service prices/settles; the Hub
  discovers and selects.
- Reserved-bandwidth / firm-ETA tiers and richer force-close dispute handling — deferred by
  p2p-0020.

## Design

### Topology

`viewrr-pay` runs alongside the Kotlin Hub on the **same node** and on both sides of a paid
transfer — it is the payer's settlement service on the downloader node and the payee's on
the seeder node. It is the only component that touches Base. The Hub talks to it over gRPC
(loopback, same trust domain). There is no server-side coordination: two peers' pay
services settle directly through the on-chain channel contract.

### Wallet (uses #2)

Every channel funding tx and every voucher signature is produced by the account's
**seed-derived EVM wallet** (#2): a secp256k1 keypair on a standard HD path derived from the
**same BIP39 seed** as the Ed25519 Identity (viewrr#142), but a distinct address not
linkable to the Identity unless the user publishes the link. The private key lives only in
the user's own opted-in node; this service holds no keys server-side beyond the running
node's session. The chain client from #2 (go-ethereum against Base) submits txs and reads
USDC/channel state.

### USDC and L2

- **Chain:** Base (EVM L2), fixed by p2p-0020 §Decision.3 — sub-cent fees, real USDC
  liquidity. Testnet: Base Sepolia.
- **Asset:** native Circle USDC on Base (ERC-20, 6 decimals). Amounts are integer
  micro-USDC throughout — no floats in money paths.

### Payment-channel lifecycle

A **unidirectional** state channel (Raiden/Nitro-style, but minimal): value only flows
payer → payee, so channel state is a single monotonically increasing cumulative amount.

**1. OPEN (one on-chain tx, gas paid once)**
- Payer's pay service calls the `PaymentChannel` contract on Base: `open(payee, deposit,
  expiry)`, transferring `deposit` USDC from the payer wallet into the contract. `deposit`
  **is** the max-spend cap for this channel — the contract can never pay out more.
- The contract mints a `channelId = keccak(payer, payee, salt)` and escrows the deposit
  (on-chain, non-custodial — the contract, not viewrr, holds it; refundable to payer).
- **Reuse:** before opening, the pay service checks its channel store for a live, un-expired
  channel to the same payee with remaining budget and reuses it — this is what amortizes the
  open/close gas across many downloads.

**2. UPDATE (off-chain vouchers, zero gas, per verified segment)**
- The Hub serves/receives a segment on the data plane and **verifies its hash**. Only for a
  verified segment does the Hub call the payer-side pay service (`SettleSegment`).
- The payer's pay service increments the channel's cumulative amount by the segment price and
  produces a **voucher**: an EIP-712 typed, payer-signed message

  ```
  Voucher {
    chainId, verifyingContract,   // domain — pins chain + contract
    channelId,                    // pins this channel
    cumulativeAmount,             // total authorized so far (micro-USDC), monotonic
    nonce                         // strictly increasing per channel
  }
  ```

  The voucher authorizes the payee to eventually claim `cumulativeAmount`. It is sent to the
  seeder off-chain (over the existing mesh/Hub channel).
- The seeder's pay service **verifies** each incoming voucher (`VerifyVoucher`): signature
  against the pinned payer wallet, `cumulativeAmount ≤ deposit`, and `nonce`/amount strictly
  greater than the last it holds. It keeps only the **latest** voucher. It then reports the
  new payment rate to its Hub so the `PeerPolicyEngine` can raise the throttle
  proportionally.
- **Ordering (pay-on-verified-delivery):** seeder serves segment N → downloader data plane
  verifies hash → payer signs voucher covering N → seeder receives voucher before serving
  N+1. The payer never signs for an unverified segment; the seeder stops serving if the
  voucher does not advance. Each side therefore risks at most **one segment** of trust.

**3. CLOSE / SETTLE (one on-chain tx)**
- **Cooperative close (default):** payer and payee co-sign the final state; either submits
  `close(channelId, cumulativeAmount, payerSig, payeeSig)`; the contract pays
  `cumulativeAmount` to payee and refunds `deposit − cumulativeAmount` to payer. Cheapest,
  instant.
- **Uncooperative / force-close:** if the counterparty is unreachable, the payee submits its
  latest voucher via `forceClose(channelId, voucher, payerSig)`. This starts a **challenge
  window**; during it the payer may submit a higher-`nonce` voucher to correct a stale claim.
  After the window, the contract settles on the highest-nonce voucher and refunds the payer.
- **Expiry:** after `expiry` with no activity, the payer can reclaim the full deposit
  (protects the payer against a payee that never settles).

### gRPC surface (from #1)

Channel methods this service implements on the Hub-facing gRPC server (final names agreed
with the Hub in #1):

- `OpenChannel(payeeWallet, maxSpend) → {channelId, txHash, remaining}` — open or reuse.
- `SettleSegment(channelId, segmentIndex, priceMicroUsdc) → {voucher, cumulative, remaining}`
  — payer side; called by the Hub **only for a hash-verified segment**. Idempotent on
  `(channelId, segmentIndex)`.
- `VerifyVoucher(channelId, voucher) → {ok, cumulative, paymentRate}` — payee side; validates
  an inbound voucher and returns the signal the `PeerPolicyEngine` uses for proportional QoS.
- `ChannelStatus(channelId) → {state, spent, remaining, expiry}` — for the max-spend cap UI
  and the bail-at-boundary decision.
- `CloseChannel(channelId) → {txHash, payout, refund}` — cooperative close; force-close is
  driven internally by a watcher when cooperation fails.

The Hub owns segment discovery, the DHT price quote, hash verification, throttle/QoS, and
the live ETA. This service owns funds, vouchers, signing/verification, and settlement.

### Settlement & the "no server ledger" property

There is no viewrr-side ledger. The Payments dashboard (Hub/client UI) reads channel and
USDC state **directly from Base**. This service's channel store is a local convenience
cache/state machine for the node's own channels — authoritative truth is on-chain.

## Implementation plan

Ordered phases, each independently shippable per the repo's slice discipline. Hard
dependencies: **#1** (proto + stub server) and **#2** (wallet + Base client) must land first.

1. **`PaymentChannel` contract (Base).** Solidity: `open` / cooperative `close` /
   `forceClose` + challenge window / `expiry` reclaim; deposit as hard cap; EIP-712 voucher
   verification; strictly-increasing nonce; USDC ERC-20 escrow. Foundry unit + fuzz tests.
   Deploy to Base Sepolia. *(Depends on: nothing in-repo; can proceed in parallel with 2.)*
2. **Voucher module (pure Go).** Build/sign/verify EIP-712 vouchers using the #2 wallet;
   monotonic cumulative + nonce; micro-USDC integer accounting; golden-vector and
   replay-rejection tests. No I/O — the most-tested unit. *(Depends on: #2 signing.)*
3. **Channel manager.** Open/reuse/close lifecycle over the #2 chain client; local channel
   state store (channelId, deposit, latest voucher each side, expiry); reuse lookup;
   force-close + challenge-window watcher. *(Depends on: 1, 2, #2.)*
4. **gRPC wiring.** Implement the channel RPCs on the #1 stub server; integrate the
   pay-on-verified-delivery path (`SettleSegment` on the Hub's verified signal), the
   `VerifyVoucher` payee path, and the payment-rate signal the `PeerPolicyEngine` consumes.
   *(Depends on: 1, 2, 3, #1 proto frozen.)*
5. **Settlement & close paths.** Cooperative close, force-close monitor, expiry reclaim,
   refund reconciliation; confirm the dashboard reads chain state directly (no ledger).
   *(Depends on: 3, 4.)*
6. **Testnet e2e + pre-mainnet gate.** Full open → N verified segments → cooperative close on
   Base Sepolia, plus force-close and over-cap paths; gas/amortization measurement.
   **Blocked from mainnet by #9 (legal review) and by confirmation of the network decision
   (#8).**

**On the network decision (#8):** the bandwidth L2 is **already decided — Base — by
p2p-0020**, so this path is *not* blocked on #8 for its chain choice. #8 is the *storage
backstop* network decision (Sia/Filecoin/Storj + USDC↔token swap) and does not gate
bandwidth channels. It is referenced only so that, if the org later consolidates on a single
chain org-wide, this design is revisited — see Open questions.

## Open questions & risks

- **Build vs adopt the channel contract.** Minimal in-house unidirectional channel (this
  design) vs adopting an audited framework (Nitro/state-channels, or a Base-native micro-
  payment primitive). Money-critical contract → strong bias to reuse an audited base. Decide
  before Phase 1 hardens.
- **Network confirmation (#8).** p2p-0020 fixes Base for bandwidth. Track #8 only in case the
  broader network decision changes the shared chain; do not re-open unless it does.
- **Segment price granularity vs gas-free reuse.** Very small per-segment prices keep the cap
  fine-grained but grow voucher/nonce churn; confirm segment pricing units with the Hub's
  USDC/GB quote (p2p-0020 §Decision.4).
- **Who signs first each round.** Pay-on-verified-delivery means the seeder extends one
  segment of trust. Confirm the seeder's bail policy (stop after one unadvanced voucher) with
  the Hub's `PeerPolicyEngine`.
- **Force-close challenge window length** vs Base finality vs UX (funds locked during
  challenge). Needs a concrete parameter.
- **Content-law exposure (#9).** Paying for potentially copyrighted bytes is heavier exposure
  than free sharing (p2p-0020 §Consequences). **Legal counsel required before any mainnet
  money movement.** Hard gate.
- **Counterparty griefing:** a peer that takes payment then throttles. Mitigated by
  per-segment granularity + bail + max-spend cap; residual risk is one segment.

### Trust boundaries & anti-abuse (money-movement security note)

- **Payer node ↔ seeder node — mutually distrusting.** The channel contract + signed vouchers
  are the only settlement authority. Each side risks at most one segment. Neither trusts the
  other's software.
- **Hub (Kotlin data plane) ↔ pay service — same node, same trust domain (loopback gRPC).**
  The pay service treats a `SettleSegment` call as the "verified-delivery" trigger and
  **never** releases a voucher without it; hash verification stays in the data plane.
- **Pay service ↔ Base — the contract is the trust anchor.** It enforces payout ≤ deposit and
  monotonic nonce; nothing off-chain can exceed the cap.
- **viewrr server — zero trust required.** It holds nothing, learns nothing, keeps no ledger;
  the dashboard reads chain directly (reaffirms p2p-0010).

**Double-spend / replay protections**

- **On-chain monotonic settlement:** strictly increasing `nonce` and non-decreasing
  `cumulativeAmount`; the contract only settles the highest-nonce voucher.
- **Cap enforcement:** `cumulativeAmount ≤ deposit` on-chain — the deposit is the max-spend
  cap; over-cap vouchers are rejected on-chain and off-chain.
- **Domain binding:** EIP-712 domain pins `chainId`, `verifyingContract`, and `channelId`, so
  a voucher cannot be replayed on another chain, contract, or channel.
- **Signature binding:** vouchers verified against the pinned payer wallet address only.
- **Force-close challenge window:** defeats a payee settling a stale/lower voucher; payer
  submits the true latest during the window.
- **Idempotent `SettleSegment`** keyed on `(channelId, segmentIndex)`: a retried Hub call
  cannot double-increment the cumulative amount.
- **Expiry reclaim:** protects the payer from a payee that never settles.

## Verification

- **Voucher unit tests (pure Go):** golden EIP-712 vectors; monotonicity enforced; replay /
  wrong-chain / wrong-channel / wrong-signer rejected; over-cap rejected; micro-USDC integer
  accounting (no float drift). This is money code — bias to more tests.
- **Contract tests (Foundry):** open, cooperative close, force-close + challenge (honest and
  stale-voucher attacker), expiry reclaim, over-cap and under-deposit reverts; fuzz on
  (amount, nonce) ordering; gas snapshots for open/close to prove amortization.
- **Integration (Base Sepolia):** end-to-end open → N verified segments → cooperative close;
  force-close path; bail-at-cap; channel reuse across two downloads shows one open/close pair.
- **Hub e2e (with #1 stub):** pay-on-verified-delivery holds (no voucher without a verified
  segment); `VerifyVoucher` emits a payment-rate signal that scales the throttle
  proportionally; max-spend cap halts spend at the boundary.
- **Non-custody assertion test:** no code path leaves user funds in this service; funds are
  only ever in the user wallet or the channel contract.
- **Secrets check:** no private key or seed is ever logged (`log/slog` audit).
- **Manual gate:** legal review (#9) signed off before any mainnet deploy.
