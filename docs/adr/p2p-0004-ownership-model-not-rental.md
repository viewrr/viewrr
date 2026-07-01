# 0004 — viewrr is a DRM-free ownership model, not enforceable rental

**Status:** Accepted (2026-07-01)

## Context

The docs described viewrr as "SVOD" (subscription VOD, Netflix-style) with retention
"indefinite while subscription active" — implying access is revoked when a
subscription lapses.

The entitlement model makes that unenforceable:
- The `contentKey` is sealed to the user's `publicKey` and stored in the user's own
  vault; the server holds no `secretKey` and cannot delete or claw it back.
- Playback is fully client-side and offline — no per-play license check.
- Deleting the Ktor entitlement row does nothing to a client that already holds the
  key and cached segments.

Enforceable rental would require per-playback online license checks and a
key-issuing server — directly contradicting "server never holds secrets," offline
playback, and zero-infra.

## Decision

viewrr is a **DRM-free ownership model**.

- Acquiring a title grants a **permanent, self-custody** content key. Once acquired,
  the title is owned forever.
- A **Subscription** gates *what a user may newly acquire/download*, plus seeding
  perks and storage tier. It does **not** revoke already-acquired titles.
- "SVOD / revoke on cancel" language is removed from the spec. Retention tiers
  (`Part 8`) govern *inactive-file cleanup*, not entitlement revocation.

## Consequences

- **Good:** Consistent with self-custody + offline + zero-infra. No license server.
- **Good:** Honest — we don't promise revocation the crypto can't deliver.
- **Bad:** No recurring lock-in on a per-title basis; revenue must come from
  new-catalog access, storage tiers, and perks, not from renting back what users own.
- **Bad:** Cannot host content whose license requires enforceable expiry. Accepted;
  same boundary as `P2P-ADR 0001`.
