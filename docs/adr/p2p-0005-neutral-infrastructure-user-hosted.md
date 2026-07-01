# 0005 — viewrr is neutral P2P infrastructure; content is user-hosted

**Status:** Superseded by `P2P-ADR 0008` (2026-07-01) — point 2 (no central catalog) is
reversed. Points 1 (NAS not a content origin) and 3 (payments deferred to phase-2)
still stand.

## Context

viewrr could be built as (1) a personal library, or (2) a content-distribution
service. The choice is Model 2, but framed as **neutral infrastructure**: users host
their own files, the platform is protocol + metadata registry, not a publisher — the
BitTorrent-client analogy, not a catalog service. The platform does not police the
legal rights of the files users host; content is encrypted to the owner's identity and
leaks no PII.

The neutrality framing only holds for the **protocol/client**. Two parts of the
original spec broke it by making viewrr the **origin host** and the **central index** —
historically the two things that draw liability (indexes lose; protocols don't).

## Decision

viewrr is neutral infrastructure. To make that true (not merely asserted), three
changes are adopted:

1. **NAS is not a content origin.** Content originates from users' own
   devices/hosting. The NAS runs **DHT bootstrap + Ktor metadata registry** only, plus
   an *optional paid backup* tier later. The "origin seeder of all content variants"
   role is removed.
2. **No central browseable catalog in MVP.** Discovery is **share-link + @handle +
   follows** only. There is no viewrr-served search index of user content. TMDB data
   is optional **client-side** metadata a user attaches to their own upload, never a
   viewrr-hosted directory.
3. **No payments in MVP.** BTCPay/Razorpay (`07`) are deferred. The paid layer is
   **phase 2 = creator channels** (SoundCloud/Dailymotion-style), where creators upload
   their **own** rights-cleared media.

What stays: keypair identity, self-custody keys, encrypted shares, watch party,
multi-device sync, username registry (phone book), mailbox notifications.

## Consequences

- **Good:** The neutral-infra posture is architecturally real — no central origin, no
  central index, no profiting from access. Large MVP scope cut.
- **Good:** MVP reduces to: identity + self-custody vaults + private stash + consented
  shares + multi-device sync + watch party.
- **Bad / risk:** Neutrality is a posture, not a legal guarantee; jurisdictions differ.
  The username registry + mailbox are the only central components and must stay
  content-agnostic (phone book + opaque sealed envelopes) to preserve the posture.
- **Phase 2:** Creator channels reintroduce payments and a *curated-by-creator*
  catalog for rights-cleared media — a distinct, opt-in publishing layer on top of the
  neutral base.
