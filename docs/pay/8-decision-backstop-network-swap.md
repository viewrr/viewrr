# DECISION: Backstop network = Sia (`renterd`); USDC↔SC via a treasury float topped up in bulk

- **Issue:** viewrr/pay#8
- **Status:** Proposed (decision needed)
- **Related ADRs:** p2p-0022 (OQ1, supply model + code split), p2p-0020 (USDC/L2 wallet), p2p-0021 (escrow + proof-of-storage/deal-migration), p2p-0023 (monetization invariants)

## Context

`p2p-0022` settled the supply model — a **device hot tier** (contributed member phones/desktops, best-effort, erasure-coded per `p2p-0011`) sitting on top of a **durable backstop** rented from a decentralized storage network. The backstop is "the one thing consumer devices can't provide": guaranteed durability for data that must survive mobile churn. viewrr rents it and pays for it out of protocol-fee **margin**, like an AWS bill.

`p2p-0022` OQ1 left two coupled choices open, and this doc resolves both:

1. **Which network** backstops durability — Sia vs Filecoin vs Storj.
2. **How viewrr pays it.** Users and contributors pay **USDC on Base** (`p2p-0020`/`p2p-0021`), but every candidate network settles in **its own native token** (SC / FIL / STORJ). The Go service (`viewrr-pay`) must convert USDC→native-token internally, invisibly to users. This is viewrr's own treasury paying a supplier — **not** custody of user funds, **not** money transmission — so the `p2p-0020` non-custodial shield is preserved (`p2p-0023` invariant #1/#3).

Two design forces dominate the network choice:

- **Auto-repair dissolves machinery.** `p2p-0021` Decision 6/7 (deal migration + a contract-as-queue reassignment matcher) and `p2p-0021` OQ1 both note that a network with **native auto-repair removes most of that machinery for the backstop tier**. A network that self-heals its own redundancy is worth a lot to a solo builder.
- **Go-native integration.** The settlement service is Go specifically because the storage/chain tooling (`renterd`, Lotus) is Go-native (`p2p-0022` Rationale). The easier the client, the better.

This decision drives implementation issues **#2** (seed-derived EVM wallet + L2 client — now also the treasury wallet surface), **#5** (Sia/Filecoin backstop integration), and touches **#4** (escrow accounting).

## Sub-decision 1 — Backstop network

### Option: Sia

- **Durability model.** The renter (`renterd`) erasure-codes data (default Reed-Solomon **10-of-30**) and spreads shards across many independent hosts, each of which posts **collateral** and submits on-chain **storage proofs** (Merkle proofs) to earn payment. Crucially, `renterd` **continuously auto-repairs**: it monitors host health and re-uploads shards when redundancy drops below threshold, and migrates a dataset off failing hosts — no action from viewrr. This is exactly the self-healing that `p2p-0021` OQ1 says "dissolves most of the Decision-6/7 machinery" for the backstop tier.
- **Cost.** Historically among the cheapest decentralized storage (~$1–2/TB/month order of magnitude), priced in SC. Native **recurring-rent** model: storage lives inside funded, time-bounded **file contracts** that you top up and renew — which lines up cleanly with the recurring-rent direction of issue #7 / `p2p-0021` OQ2.
- **Go tooling.** `renterd` / `hostd` / `walletd` are all first-party Go daemons with a **REST (HTTP/JSON) API** and a Go client. For a Go settlement service this is the cleanest possible integration: form/fund/renew contracts, upload/download objects, and let the daemon own repair. An embedded Sia wallet manages the SC balance.
- **Native token.** **Siacoin (SC)** — its own L1, **not** an ERC-20. Low market cap, thinner liquidity, listed on a limited set of CEXs, and with **no deep on-chain DEX** path (there is no first-class USDC↔SC pool because SC isn't an EVM asset). This is the sharp edge, and it lands entirely on sub-decision 2.
- **Pros.** Native auto-repair (removes bespoke repair/migration for the backstop); Go-native REST client; cheapest; recurring-rent native; erasure coding built in; mature renter daemon; genuinely decentralized (no company gatekeeper).
- **Cons.** SC is **illiquid/volatile**, making the swap and float management the hard part; smaller host ecosystem; viewrr must run/maintain `renterd` + an SC-funded wallet; host quality varies; SC acquisition is CEX-only (no DEX).

### Option: Filecoin

- **Durability model.** Storage deals with storage providers, backed by the **strongest cryptographic proofs** in the space — **PoRep** (proof of replication) + **PoSt** (proof of spacetime), verified on-chain. But there is **no native auto-repair**: if a provider fails, redundancy is viewrr's problem — you make multiple deals across providers yourself and re-deal/re-upload on failure. That **rebuilds the deal-migration/repair machinery `p2p-0022` wanted to dissolve** (`p2p-0021` OQ1 says as much: "Filecoin has proofs but no native auto-repair — needs a layer on top").
- **Cost.** Headline deal price is often near-zero/subsidized, but true cost includes retrieval, deal-making overhead, and the reliability layer you must add. FIL funds gas + deal collateral.
- **Go tooling.** **Lotus** (reference node) and Boost are Go-native, but **client-side deal-making is heavier and clunkier** than Sia's REST daemon. FVM (EVM-compatible) exists, and hosted abstractions (Web3.Storage / Lighthouse-class) can hide deal-making — at the cost of reintroducing a third-party service.
- **Native token.** **FIL** — large cap, **deeply liquid**, on every major CEX, wrappable, and an **EVM-side presence** via FVM. This makes the swap/float *easy* — the opposite trade-off from Sia.
- **Recurring.** Deals are **fixed-duration**; renewal = new deals. No native streaming rent.
- **Pros.** Strongest proofs; FIL liquidity makes the currency boundary trivial; huge capacity/ecosystem; EVM FVM.
- **Cons.** **No auto-repair** — you rebuild repair + deal-migration (the exact machinery this stack tried to shed); deal-making friction; heavier Go integration; PoRep/PoSt are **overkill for a backstop that already sits under viewrr's own erasure-coded mesh**; more moving parts for a solo dev.

### Option: Storj (considered, rejected)

S3-compatible gateway, node operators paid in **STORJ (ERC-20)** — so the swap is trivial and integration is a one-liner (S3 API). But Storj is **semi-centralized**: a Storj-Labs-run **satellite** coordinates metadata/billing and can offboard a customer — a company gatekeeper in the trust path. That reintroduces exactly the vendor dependency the decentralized-backstop choice exists to avoid; at that point plain S3/R2 is simpler and more honest. **Rejected** on the decentralization ethos, not on tech.

### Recommendation

**Sia, integrated via `renterd`.** The decisive factor is **native auto-repair**: it dissolves the `p2p-0021` Decision-6/7 deal-migration + reassignment-matcher machinery *for the backstop tier*, which is the single biggest complexity win available to a solo builder. It is Go-native with a clean REST API (best fit for the Go service), the cheapest option, and recurring-rent-native (aligns with #7). Filecoin's advantages — liquid FIL and stronger proofs — don't pay off here: the proofs are overkill under viewrr's own mesh, and the no-auto-repair gap re-imposes the very machinery we're trying to shed. Sia's one real weakness (illiquid SC) is a **currency-boundary problem, not a durability problem**, and is fully handled in sub-decision 2 by keeping the swap out of the settlement hot path. Keep Filecoin on the shelf as a possible **second, higher-assurance backstop tier** if a future data class needs PoRep/PoSt-grade proofs (ties into placement policy, `p2p-0022` OQ2).

## Sub-decision 2 — USDC↔token swap

The problem: escrow margin arrives as **USDC on Base** (viewrr's revenue), but the Sia backstop must be paid in **SC**. SC is not an ERC-20 and has no DEX path, so the naive "swap at deal time" options are weak. Framing matters: this is **viewrr's own treasury converting its own revenue to pay a vendor** — not touching user funds.

### Options

- **(a) On-chain DEX swap, per deal.** Swap USDC→native at deal time on a DEX. For Sia this is a **non-starter** — SC isn't an EVM asset, so there's no USDC↔SC pool; you'd need a thin wrapped-SC bridge with heavy slippage/MEV, then cross to the Sia L1 anyway. (Only viable at all for FIL/STORJ.) Per-deal price risk, high slippage on illiquid tokens, and it puts a swap in the settlement critical path. **Rejected.**
- **(b) CEX swap, in bulk.** viewrr's treasury buys SC on a centralized exchange (via API), withdraws to the `renterd`/`walletd` wallet. Deep enough liquidity, and bulk buys amortize spread. Cost: a **viewrr-owned, KYC'd exchange account** (viewrr's *own* corporate KYC, not users') and API-key custody risk. This is the standard way a treasury acquires a token.
- **(c) Bridge.** Cross-chain USDC→token bridging — relevant only to EVM-side assets (FIL/STORJ), not SC. Adds bridge-hack risk and still needs a swap somewhere. **N/A for Sia.**
- **(d) Keep a token float (pre-funded reserve).** viewrr holds a working SC balance in the `renterd` wallet, sized to *N* months of backstop spend; contracts draw from the float; the float is refilled in **bulk** when it drops below a threshold. This **decouples swap frequency from deal frequency** — amortizing slippage, hiding SC volatility from the settlement path, and matching the "AWS bill / treasury reserve" framing in `p2p-0022` Decision #2 and Consequences ("a small viewrr-owned float").

### Recommendation

**Float + periodic bulk top-up via CEX — options (d) + (b) together; never a per-deal on-chain swap.** Concretely:

1. Protocol-fee margin accrues to a **viewrr treasury USDC address on Base** (downstream of the escrow skim, `p2p-0021` Decision 1/2).
2. A treasury job monitors the SC float in the `renterd` wallet. When it falls below a threshold (e.g. < *k* months runway), it **bulk-buys SC on a CEX** from treasury USDC and withdraws to the wallet.
3. Backstop deals just **spend SC from the float** — no swap in the settlement path, so volatility and slippage never touch a live deal.

This keeps the whole currency boundary an **internal treasury operation, invisible to users** (`p2p-0023` invariant #3), amortizes the cost of SC illiquidity, and stays firmly non-custodial: it's viewrr's own revenue paying a supplier, not user funds being routed or netted. **Get this specific arrangement into the #9 legal review** — operating a CEX account and holding an SC reserve should be confirmed as a corporate treasury activity, not money transmission.

## Consequences

- **Issue #2 (wallet + L2 client) grows a second domain.** The user/contributor wallet stays exactly as `p2p-0020` specifies — seed-derived secp256k1 EVM wallet, USDC on Base, unlinkable to the Ed25519 Identity. But the Go service now *also* owns a **treasury surface** that #2 must scope: (i) a viewrr **USDC treasury address on Base** receiving margin, (ii) a **Sia wallet** (`walletd`/embedded in `renterd`) holding the SC float, and (iii) a **CEX API integration** for bulk top-ups. Two clearly separated wallet concerns — *user funds we never touch* vs *viewrr treasury we do own* — must not be conflated in code or key management.
- **Issue #5 (backstop integration) has a concrete build target.** Integrate the **`renterd` REST API** (form/fund/renew file contracts, upload/download erasure-coded objects) and **rely on `renterd` auto-repair** — do **not** build a bespoke repair/reassignment loop for the backstop tier. Lotus/Filecoin is explicitly out for the primary backstop. Open item: run our own `renterd`/host set vs a hosted `renterd`, and retrieval latency from the (cold) backstop when the hot tier loses a segment.
- **Issue #4 (escrow accounting) — keep SC entirely outside the contract.** The escrow contract handles **USDC only**: buyer pays in, providers are paid out, viewrr's margin is skimmed as a coded protocol fee (`p2p-0021` Decision 1/2). The contract **must not know about SC or the backstop.** The backstop is a **post-margin, off-chain treasury expense**: margin USDC lands in the treasury → CEX buy → SC float → `renterd` spend. Accounting must reconcile that pipeline (USDC margin in vs SC spent out) as an operating cost, separate from the on-chain escrow ledger — otherwise the non-custodial boundary blurs.

## Open questions

1. **Float sizing + hedging.** How many months of backstop runway to hold as SC, the rebalance threshold, and whether to hedge SC price exposure at all (a treasury reserve in a volatile L1 token carries mark-to-market risk).
2. **CEX selection + SC delisting risk.** Which exchange supports **SC withdrawals + API** under viewrr's jurisdiction's KYC, and the fallback if SC gets delisted or withdrawals are suspended (single-exchange dependency for the whole backstop).
3. **Self-hosted vs hosted `renterd`.** Run our own `renterd` (and possibly `hostd`) vs use a managed renter provider — the classic ops-vs-control trade for a solo builder.
4. **Backstop retrieval SLA.** Is Sia retrieval fast enough when the device hot tier loses a segment and must rehydrate from the cold backstop? Feeds the placement policy (`p2p-0022` OQ2 — what data is pushed to the backstop at all).
5. **Second backstop tier?** Whether a future high-assurance data class justifies a **Filecoin** (PoRep/PoSt) tier alongside Sia, and how placement policy routes between them.
6. **Legal confirmation.** Does viewrr operating a CEX account + holding an SC float alter the non-custodial / money-transmission analysis? Must be cleared in **#9** before any backstop spend ships (`p2p-0023` "get real legal review").
