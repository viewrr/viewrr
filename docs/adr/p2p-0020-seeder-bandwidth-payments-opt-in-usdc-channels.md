# 0020 — Optional peer-settled bandwidth payments (opt-in wallet, USDC L2 channels, proportional QoS)

**Status:** Accepted (2026-07-04)

## Context

Seeding costs the seeder real bandwidth, and in expensive-internet countries that
cost is a genuine disincentive. `p2p-0011` deferred "billing to phase-2 payments"
and `p2p-0005`/`CONTEXT-p2p` established a **free / self-hosted** baseline. This ADR
defines that phase-2 layer: a way for a downloader to *optionally* pay a seeder for
priority bandwidth, turning a would-be free-riding "greedy actor" into a revenue
source for the mesh — without breaking the free path, the pseudonymity of
`p2p-0006`/`p2p-0008`, the no-broker guarantee of `p2p-0010`, or the founder's
free-software mission.

Several framings in the original proposal were corrected during design:
- You do **not** mint a USD-pegged coin per user. USDC/USDT are single tokens
  issued by Circle/Tether. Each account instead gets a **wallet (address)** that can
  hold an existing stablecoin.
- **Wallet creation is free**; only *transactions* cost gas. Gas is structural
  (it pays validators and deters spam), so no USD-pegged chain is genuinely gasless.
  **Payment channels** make per-payment cost ≈ 0 (fee only at open/close).
- MetaMask is EVM/secp256k1; the viewrr Identity is Ed25519 — different curves.
  The payment wallet is therefore a *separate* keypair, not the Identity key.

## Decision

1. **Opt-in and peer-settled, never platform-brokered.** Free serving stays the
   default. Payment is an option a seeder and a downloader each choose. viewrr is a
   **protocol, not a money transmitter** — it never holds, routes, nets, or brokers
   funds, and ships no in-app fiat off-ramp. (Legal shield; reaffirms `p2p-0010`.)
2. **Wallet is opt-in in profile settings — account creation does NOT create a
   wallet.** When a user opts in, a wallet is derived from the **same BIP39 seed**
   as the Identity (`#142`), on a standard HD path — one recovery phrase restores
   both, MetaMask/Rabby can import it, and the wallet is a distinct secp256k1
   address **not linkable to the Ed25519 Identity** unless the user publishes the
   link. Non-opted-in users have no wallet and keep full pseudonymity.
3. **USDC on an EVM L2 (Base) with payment channels.** L2 for sub-cent fees and
   real USDC liquidity; a pairwise **payment channel** carries per-segment
   micropayments off-chain (fee only at channel open/close, amortized by reuse).
4. **Market-priced, cheapest single-source paid pull.** A seeder sets a price in
   **USDC/GB** (a "declare your internet plan" calculator only *suggests* the price;
   the plan is never trusted or verified — the market caps it). The DHT availability
   lookup is extended to return a **price quote** per peer. A paid pull selects the
   **single cheapest** seeder and opens/reuses **one** channel with it. Free pulls
   keep the full multi-source swarm; paid pulls are single-source by design.
5. **Per-segment pay-on-verified-delivery.** A segment's micropayment is released
   only after its hash verifies — no pay-then-serve-garbage. The downloader sets a
   **max-spend cap** and can bail at any segment boundary.
6. **Payment buys proportional priority, granted locally.** Free serving is
   fair-share throttled (the `FairShareManager`/`TokenBucketThrottle` of `#160`).
   Paying raises the seeder's throttle **proportionally** — more payment/sec → more
   Mbps — decided by the seeder's own `PeerPolicyEngine` from the channel receipts
   it sees. No server directive (reaffirms `p2p-0010`/`#160`).
7. **Best-effort live ETA.** Because Mbps is a proportional share of the seeder's
   current paid demand, a newly-joining payer lowers everyone's share. The download
   **ETA is therefore an estimate, recomputed live** as payers join/leave — not a
   guarantee. Pay-per-verified-segment + the max-spend cap mean drift never
   overcharges: a slower download simply finishes later and pays only for bytes
   actually delivered. No bandwidth reservation / admission-control infra.

## Considered options

- **Mandatory / server-brokered payments (rejected):** makes viewrr a money
  transmitter (MSB licensing, AML, KYC), destroys the free tier and the mission.
- **Identity key = wallet key (rejected):** needs a Solana-style Ed25519 chain and
  makes every payment de-pseudonymize the Identity. Separate seed-derived EVM wallet
  chosen instead.
- **Per-chunk L2 settlement without channels (rejected for MVP):** simpler, but
  can't reach true per-segment granularity; channels chosen for the ~0 per-payment
  cost.
- **Paid multi-source swarm (deferred):** would need Lightning-style payment
  *routing* across many channels — much more infra. Single-source paid pull chosen.
- **Reserved bandwidth with firm ETA (rejected):** needs admission control and
  "seeder full" states, and isn't literally proportional. Best-effort live ETA
  chosen.
- **Two-tier free-vs-priority throttle (rejected):** proportional (continuous)
  chosen so pay-more-get-more is smooth, not a binary lane.

## Consequences

- **Mission + free tier intact:** anyone who never opts in never touches crypto;
  the mesh works fully for free (`p2p-0005`).
- **Pseudonymity scoped, not lost:** a *paying pair* reveals its two wallet
  addresses to each other (unavoidable), but the server learns nothing and
  non-paying users are unaffected (`p2p-0006`/`p2p-0008` hold for the free mesh).
- **KYC / tax is the user's, not viewrr's:** cashing out to fiat goes through the
  user's own KYC'd exchange; seeder earnings are their taxable income. viewrr stays
  non-custodial so it is not the regulated entity. **Get jurisdiction-specific legal
  counsel before shipping** — and note that paying for *copyrighted* bytes is a
  heavier content-law exposure than free sharing (separate, unresolved here).
- **`#160` gains a second input:** the `PeerPolicyEngine` now weighs
  payment-priority alongside fair-share.
- **New surfaces to build:** profile opt-in + seed-derived EVM wallet; price quote
  in the DHT lookup; the pairwise channel (open/reuse/close, per-segment
  pay-on-verified-delivery, max-spend cap); a "Payments" dashboard that reads the
  chain directly (no server ledger); the price-tier slider with live ETA.
- **Upgrade paths deferred:** payment routing for paid multi-source; reserved-ETA
  tiers; richer dispute handling on channel force-close.
