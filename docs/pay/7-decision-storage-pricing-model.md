# DECISION: Recurring rent vs one-time storage pricing

- **Issue:** viewrr/pay#7
- **Status:** Proposed (decision needed)
- **Related ADRs:** p2p-0004, p2p-0021, p2p-0022 (+ p2p-0023, p2p-0020)

## Context

The storage marketplace (`p2p-0021`) sells **pooled GB, not media files**. Its
worked example — "$5 one-time for 15–20 GB" — is flagged as economically broken in
`p2p-0021` OQ2: storage is an **ongoing** cost to the provider (bytes held
indefinitely), so a one-time fee cannot fund perpetual hosting. `p2p-0023` restates
this as a top-tier unresolved risk. We must pick a pricing/settlement model **before
the escrow contract client (#4) goes deep**, because the choice dictates the shape of
the escrow release schedule, the proof-of-storage cadence (#6), and how the
Sia/Filecoin backstop deals are funded (#5).

This is a settlement decision, not just a pricing one: the payment *schedule* is
what the Go service has to encode on-chain. One-time = a single escrow release;
recurring = a stream of releases gated by repeated storage proofs over time.

**The `p2p-0004` tension.** `p2p-0004` chose an **ownership model, not rental**, and
deliberately deleted "SVOD / revoke on cancel" language. A pricing option literally
named "recurring rent" collides with that brand at first glance. The collision must
be resolved explicitly (see Recommendation) rather than waved away — but note the two
live on **different axes**: `p2p-0004` governs **media entitlement** (a self-custody
content key the server can never claw back), while this issue governs **raw storage
capacity** (bytes a provider physically holds, enforceable by simply ceasing to hold
them). They are orthogonal, the same way `p2p-0021` separates the copyright axis from
the money-transmission axis.

## Options

### Option A — Recurring rent (per GB / month, streamed via escrow)

**Mechanics.** Buyer funds an escrow with a per-GB-per-month rate for a chosen (or
open-ended, auto-renewing) period. The contract **streams** payouts to providers over
the storage period, releasing each period's share only against a fresh storage proof
(`p2p-0021` Decision 5, PoSt-class). Reuses the `p2p-0020` payment-channel primitive
(#3) for the micro-payment stream rather than one lump release. Non-payment triggers a
grace window, then the provider is released from the deal and the erasure-coded
segments are allowed to expire.

- **Pros:** Economically sound — funds perpetual/ongoing hosting from an ongoing
  revenue stream. Matches the native model of every network we integrate: Sia
  contracts and Filecoin deals are **time-boxed and renewed**, so the backstop (#5)
  and its USDC→SC/FIL swap map 1:1 onto recurring rent with **no impedance
  mismatch**. Makes proof-over-time (#6) a first-class, load-bearing scheduler
  instead of a one-shot check. Deal migration / re-bidding (`p2p-0021` Decision 6)
  only *means* anything when deals have remaining term and value.
- **Cons:** Heaviest settlement complexity — streaming releases, a proof cadence,
  billing-period accounting, and a non-payment/expiry state machine. Requires a
  graceful data-expiry policy (what happens to bytes when rent stops). Carries the
  "rent" word that superficially clashes with `p2p-0004`'s brand.
- **Escrow/channel implications:** Escrow is long-lived, not fire-and-forget;
  payment channels (#3) are reused to amortise on-chain cost of the stream. Proof
  verification cost recurs each period (`p2p-0021` OQ4).
- **Conflict with `p2p-0004`:** *Surface* clash only. `p2p-0004` forbids revoking
  **already-acquired media**, which is impossible anyway (self-custody keys, offline
  playback). Storage rent revokes nothing a user *owns*: it stops hosting a *copy* of
  data on rented capacity. Losing a rented copy ≠ losing the title — the content key
  and any locally cached segments survive. The two models coexist cleanly, but the
  product must say so out loud.

### Option B — One-time storage pricing

**Mechanics.** Buyer pays once; escrow releases to providers on a single storage
proof. To fund perpetual holding from a single payment, the only honest construction
is an **Arweave-style endowment**: the upfront fee seeds an endowment whose yield (or
declining-storage-cost math) pays providers indefinitely. A naive one-time fee with no
endowment simply underfunds the provider after month one.

- **Pros:** Dead-simple settlement — one escrow release, no stream, no recurring
  proof scheduler, no billing state. Philosophically closest to `p2p-0004`'s "pay
  once, hold forever" ownership vibe; cleanest brand story.
- **Cons:** The naive form is **economically broken** (`p2p-0021` OQ2) — a provider
  bleeds cost forever for a one-time payment, so it abandons the data or viewrr
  subsidises it, neither sustainable for a solo builder. The only *working* one-time
  model (endowment) is research-grade: it needs a yield-bearing treasury or a
  provably-declining cost curve, reintroduces a fund viewrr manages (custody / MSB
  risk against the non-custodial invariant of `p2p-0023`), and **mismatches the
  backstop** — Sia/Filecoin will still bill viewrr on renewal, so viewrr eats
  perpetual renewal cost out of a finite one-time fee.
- **Escrow implications:** Trivial escrow, but no mechanism to gate perpetual holding
  on continued proof — so proof-over-time (#6) loses its economic teeth.
- **Alignment with `p2p-0004`:** Best brand alignment, worst economics. Aligns in
  spirit; fails on the physics of ongoing cost.

### Option C — Time-boxed lease (prepaid term, hybrid)

**Mechanics.** Buyer prepays a **fixed term** (e.g. 12 months of N GB) into escrow;
the contract streams to providers over the term and **auto-expires** unless renewed.
A bounded, prepaid recurring deal — the recurring engine of Option A with the
finite-commitment feel of Option B.

- **Pros:** Economically sound (funds the term it covers), yet gives the buyer a
  fixed, knowable price and a clean end date instead of an open-ended "rent."
  Maps directly onto Sia/Filecoin's fixed-duration contracts (#5). The lease term
  naturally bounds deal-migration term math (`p2p-0021` Decision 6). Softer brand
  language ("prepaid storage plan") than "rent."
- **Cons:** Still requires the streaming/proof machinery of Option A (so no
  settlement simplification over A), plus renewal UX and an expiry policy. Prepaying a
  long term locks buyer capital.
- **Escrow implications:** Same long-lived escrow + proof cadence as A, with a hard
  termination boundary the contract enforces.

## Recommendation

**Adopt Option A (recurring rent, per GB / month, streamed via escrow), packaged to
buyers as Option C's time-boxed prepaid leases with auto-renew.** Recurring is the
only model whose economics are not broken and whose settlement shape matches the
Sia/Filecoin backstop we already committed to in `p2p-0022`. Option B's naive form is
ruled out by `p2p-0021` OQ2; its only working variant (endowment) reintroduces a
managed fund that fights the non-custodial invariant and still can't reconcile with a
renewing backstop.

This does **not** violate `p2p-0004`. `p2p-0004` is an **entitlement** decision:
acquired titles are self-custody and can never be revoked, and revenue must come from
"new-catalog access, storage tiers, and perks, not from renting back what users own."
Recurring storage rent is exactly a **storage tier** — `p2p-0004` explicitly blesses
it. Crucially, storage rent is *enforceable by non-provision* (a provider stops
holding bytes), which is precisely the enforceable-expiry capability `p2p-0004` said
viewrr **lacks for media** — and that asymmetry is *why* the two models are safe to run
side by side. We must state the brand split plainly in product copy: **you own your
media forever (self-custody key); you rent raw storage capacity by the month (a hosting
service).** Renting or losing a pooled copy of your own file never touches your
ownership of a title, because the content key and cached segments are yours regardless.

## Consequences

Implementing recurring rent forces, across the open issues:

- **#4 (storage escrow contract client):** the escrow is **long-lived with streamed
  releases**, not a single payout. It must encode a per-GB-per-month rate, a billing
  period, a renewal/auto-expire path, and a non-payment → grace → release → expiry
  state machine. Design the contract interface around a stream, not a transfer.
- **#3 (USDC bandwidth payment channels):** promote the payment-channel primitive to a
  **shared streaming-settlement engine** used by both bandwidth and storage rent, so
  recurring storage micro-payments don't pay per-period on-chain gas. One primitive,
  two products.
- **#5 (Sia/Filecoin backstop):** aligns natively — backstop deals are already
  time-boxed and renewed. The **USDC→SC/FIL swap must itself be recurring** (funded
  from margin each period per `p2p-0022` Decision 2), and backstop renewal cadence
  should track the buyer's lease term.
- **#6 (proof-of-storage + deal-migration matcher):** proof-over-time becomes the
  **release gate for every billing period**, not a one-shot check — the proof
  scheduler is now on the critical path. Deal migration (`p2p-0021` Decision 6)
  inherits well-defined "remaining term / remaining value" from the lease, which the
  `new_payout − exit_penalty − handoff_cost > old_payout` test needs.
- **Brand / product surface:** requires explicit copy separating "owned media" from
  "rented storage," plus a data-expiry policy consistent with `p2p-0004`'s retention
  tiers (Part 8 = inactive-file cleanup, **not** entitlement revocation).

## Open questions

- **Billing granularity:** discrete monthly periods vs continuous per-block streaming
  — trade UX/accounting simplicity against on-chain cost.
- **Non-payment / expiry policy:** grace-window length, and how expiring rented
  segments reconcile with `p2p-0004` retention tiers and erasure-coding RF≥2.
- **Prepaid term vs open-ended:** default lease length, auto-renew default, and how
  much buyer capital gets locked.
- **Should an Arweave-style endowment be offered as a premium "pay-once" tier** on
  top of recurring, and can it be done without viewrr custodying a fund (non-custodial
  invariant, `p2p-0023`)?
- **Rate reconciliation:** how the per-GB/month rent maps to backstop token cost +
  protocol-fee margin so `buyer_paid > Σ(provider_payouts)` holds every period
  (`p2p-0021` Decision 2) — pricing/bid mechanism is still `p2p-0021` OQ3.
- **User-stores-own-media UX:** confirm product copy prevents any "I paid rent, do I
  still own it?" confusion at the entitlement/storage boundary.
