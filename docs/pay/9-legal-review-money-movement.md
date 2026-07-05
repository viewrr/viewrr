# Legal review gate before ANY money movement ships

- **Issue:** viewrr/pay#9
- **Status:** Blocking gate — must clear before money-movement features ship
- **Related ADRs:** p2p-0020, p2p-0021, p2p-0022

> **This is NOT legal advice.** This document is a compliance *gate definition* and a
> checklist of items to raise with qualified, jurisdiction-specific counsel. Nothing
> here asserts a legal conclusion. Every item below is framed as a question for a
> lawyer, not an answer. The engineering design (non-custodial, peer-settled) is a
> *hypothesis about legal posture* that counsel must confirm or reject — it is not a
> ruling.

## Purpose

viewrr-pay (the Go settlement service, p2p-0022) is the only component in the system
that touches real value. It interacts with on-chain USDC payment channels, a
smart-contract storage escrow, and a Sia/Filecoin durability backstop paid in the
network's native token. Once any of that ships, the project is moving real money for
real people.

The whole monetization suite (p2p-0020/0021/0022, indexed by p2p-0023) rests on one
load-bearing legal assumption: that because viewrr **never holds, routes, nets, or
brokers user funds** — payments are peer-settled or escrowed on-chain, and viewrr's
margin is a coded protocol fee — viewrr is a *protocol, not a money transmitter*. That
assumption is the entire legal shield. It has been asserted by engineers in ADRs; it
has **not** been confirmed by a lawyer. This gate exists to get that confirmation (or a
correction) from counsel *before* any value-moving code reaches production, when
changing the design is still cheap.

The ADRs themselves flag this repeatedly: p2p-0020 says "get jurisdiction-specific
legal counsel before shipping," and p2p-0023's "biggest unresolved risks" list ends
with "Get real legal review before shipping any money movement." This document is the
gate that discharges those flags.

## Scope of money movement being reviewed

Each distinct value-moving path counsel should review, and the pay issue that builds it:

1. **Bandwidth payment channels (pay#3, from p2p-0020).** A downloader opens a pairwise
   USDC payment channel on an EVM L2 (Base) with a single seeder and streams
   per-segment micropayments (pay-on-verified-delivery) for priority bandwidth. viewrr
   is not a party to the channel; the two peers' wallets settle directly. Wallets are
   opt-in, seed-derived (secp256k1), and not linked to the Ed25519 Identity.

2. **Storage-marketplace smart-contract escrow (pay#4, from p2p-0021).** Buyers pay
   into an on-chain escrow contract for pooled storage-GB; the contract auto-splits
   payouts to providers against storage proofs and skims viewrr's margin as a coded
   protocol fee (`buyer_paid > Σ provider_payouts`). viewrr never holds or pays out —
   the contract does. Sellable capacity is member-contributed GB (mandatory minimum +
   optional dedicated).

3. **Sia/Filecoin durability backstop payments (pay#5 / pay#8, from p2p-0022).** viewrr
   rents guaranteed durability from Sia/Filecoin and pays the network in its native
   token (SC/FIL), funded from viewrr's own protocol-fee margin — framed in the ADR as
   "viewrr's treasury paying an infrastructure vendor," like an AWS bill, invisible to
   users.

4. **USDC → SC/FIL internal swap / treasury float (pay#8, from p2p-0022).** The Go
   service converts USDC margin into SC/FIL to pay the backstop, via a DEX/swap or a
   small viewrr-owned float. This is a currency-conversion + treasury operation inside
   viewrr's own service, asserted to be internal and not user custody.

5. **Storage pricing surfaces to end users (pay#7, from p2p-0021).** Provider bidding,
   buyer quotes/ETAs, recurring-rent vs one-time economics — the consumer-facing
   pricing and disclosure layer for the storage product.

## Legal/compliance review checklist

Raise each of the following **with counsel as a question**. Do not let engineering
resolve any of these internally — that is precisely what this gate prevents.

**Money-transmitter / MSB status (the core question)**
- Does the non-custodial, peer-settled channel model (path #1) keep viewrr outside
  US federal MSB registration (FinCEN) and state money-transmitter licensing? Does the
  answer change in the EU (EMI/PSD2/MiCA), India (PA/PSP), or other target markets?
- Does the smart-contract escrow (path #2) — where viewrr *authors* the contract that
  takes buyer funds, splits payouts, and skims a coded margin — count as money
  transmission or as operating an unlicensed exchange/escrow, even though viewrr never
  holds keys to the funds? Does authoring/deploying the contract create liability that
  "not touching the funds" does not cure?
- Does taking a **protocol fee / arbitrage margin** on third-party payments change the
  analysis versus a pure pass-through?

**Custody vs non-custody posture**
- Is the "protocol, not custodian" characterization defensible for each path, or does
  any path (escrow authorship, the treasury float in #4, force-close dispute handling)
  cross into constructive custody or control of user funds?
- Does the **treasury float / USDC→SC-FIL swap** (path #4) make viewrr a holder of
  value / a party that could be deemed to be transmitting or exchanging?

**KYC / AML applicability under the self-custody seed model**
- Given wallets are self-custodial and seed-derived (user holds keys; viewrr never
  sees fiat or off-ramps), do KYC/AML/CTF obligations attach to viewrr at all, or do
  they sit entirely with the user's own KYC'd exchange at cash-out (as the ADR
  assumes)?
- Does viewrr operating the escrow contract or the matching/settlement service create
  a KYC/travel-rule obligation despite pseudonymity?

**Jurisdiction**
- Which jurisdictions govern given a solo founder in India, users worldwide, and
  on-chain L2 settlement? Where can viewrr be sued / regulated, and which markets
  should be geofenced out at launch to limit exposure?

**USDC / stablecoin issuer terms**
- Do Circle's USDC terms of use permit this programmatic channel/escrow usage? Any
  restrictions on building payment products on USDC, or on the L2 (Base) chosen?

**Smart-contract escrow liability**
- What is viewrr's liability for bugs/exploits in the escrow contract that lose user or
  provider funds (path #2)? Does authoring the contract create product-liability or
  fiduciary exposure? What disclaimers/audits does counsel require before deploy?
- Liability around force-close, deal-migration slashing/bonds, and disputed payouts.

**Consumer protection for storage pricing (path #5 / pay#7)**
- What disclosures are required for the storage product's pricing, especially the
  unresolved **recurring-rent vs one-time-fee** question (p2p-0021 OQ2 flags the
  one-time model as economically broken)? Refund/cancellation rights? Durability-SLA
  representations and what happens on data loss?

**Content-law exposure (explicitly called out in the issue)**
- Assess the **content-law exposure of paying for copyrighted bytes** (p2p-0020):
  paying a seeder for specific (possibly copyrighted) content is a heavier exposure
  than free P2P sharing. Does the bandwidth-payment product create secondary/inducement
  liability the free mesh avoids?
- Abuse/takedown stance for **arbitrary buyer-stored content** in the storage
  marketplace (p2p-0021 OQ5), reconciled with the p2p-0010 de-index-only /
  no-backdoor / encryption-at-rest posture. Is a de-index-only response legally
  sufficient for illegal stored content?

**Tax / 1099 and reporting surfaces**
- Does viewrr have any information-reporting obligation (US 1099-class, or equivalent)
  for seeder earnings or provider payouts it facilitates, even non-custodially?
- Any tax-collection or VAT/GST obligation on the protocol-fee margin viewrr earns?
- Confirm the ADR assumption that seeder/provider earnings are "the user's own taxable
  income" and not viewrr's reporting burden.

## Gate definition

**"Cleared" means:** qualified, jurisdiction-specific counsel has reviewed all five
money-movement paths above and delivered a written opinion that, at minimum:
1. confirms (or corrects) the non-custodial "protocol, not money transmitter" posture
   for each path, and identifies any registration/licensing required in the launch
   jurisdictions;
2. states the KYC/AML obligations (if any) that attach to viewrr;
3. addresses the content-law and abuse/takedown questions from the issue; and
4. lists any required changes, disclosures, geofencing, or disclaimers — which are then
   tracked as their own issues and completed before the affected path ships.

The written opinion (or a summary + link) is attached to this issue, and viewrr/pay#9
is closed only when items 1–4 are satisfied.

**Blocked until cleared:** no value-moving code ships. Specifically the gate blocks
**pay#3** (bandwidth channels), **pay#4** (storage escrow), and **pay#5** (backstop
payments); it also gates **pay#8** (USDC↔SC/FIL swap / treasury) and the user-facing
pricing in **pay#7**. Non-money work — gRPC scaffolding, the mesh data plane
(Kotlin/worklet), design docs, and dry-run/testnet-only code with no mainnet value —
may proceed, but must not be promoted to a path that moves real value until this gate
clears.

## Open questions

- **Counsel selection:** who is qualified across the relevant axes (crypto/MSB, content
  law, multi-jurisdiction) and affordable for a solo founder? One firm or specialists
  per axis?
- **Launch jurisdiction scope:** ship into one permissive jurisdiction first and
  geofence the rest, or seek a global opinion up front? Which markets to exclude at MVP?
- **Testnet boundary:** how far can pay#3/#4 be built and demoed on testnet (no real
  value) before they count as "money movement" for this gate? Define the precise line.
- **Escrow-contract audit:** does counsel require an independent smart-contract security
  audit as a precondition, and is that a separate gate from the legal opinion?
- **Recurring-rent decision (p2p-0021 OQ2):** must the rent-vs-one-time economic model
  be finalized *before* counsel reviews the storage product, since it changes the
  consumer-protection surface?
- **Copyright vs money-transmission are orthogonal (per p2p-0021):** confirm counsel
  addresses both axes; clearing one does not clear the other.
- **Does clearing this gate need re-review** if the chain, contract standard, or backstop
  network (p2p-0022 OQ1) changes after the opinion is issued?
