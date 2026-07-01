# 0009 — Peer selection by Plus Code proximity + uplink speed

**Status:** Accepted (2026-07-01)

## Context

A title (e.g. "Interstellar 1080p", keyed by `contentUUID`) may be held by many peers.
All copies of the same format are treated as **interchangeable sources** — there is no
"canonical bytes" concept; a peer's file does not become the authoritative title. The
question is purely *which source to pull from*.

## Decision

When a user requests a title, select the serving peer by:
1. **Proximity** — nearest by Plus Code (location short code), then
2. **Uplink speed** — fastest measured upload among nearby peers.

Pull the files from that peer. On drop/slowdown, use the **fallback chain** (`04`) to
the next-best peer. Selection is entirely **client-side** (no central reputation),
consistent with the pseudonymous, metadata-only catalog (`P2P-ADR 0008`).

## Consequences

- **Good:** Fast, local-first delivery; no central coordination or identity map.
- **Residual risk — mislabeled file** under a popular `contentUUID`. Hyperdrive verifies
  bytes against the drive's own hash, but not that the drive *is* the labeled title.
  **Mitigations (now MVP — this is the catalogue-integrity defense, not anti-Sybil):**
  1. **Client-side TMDB sanity-check (MVP):** after pull, verify file duration/resolution
     against TMDB metadata; fall back to the next owner on mismatch. Catches gross fakes
     automatically. (Promoted from deferred — the operator's actual concern is catalogue
     poisoning, and this is its content-level fix.)
  2. **Anonymous flagging (MVP):** users flag a bad copy; flag-count **de-prioritizes
     that drive** in peer selection. No identity required, pseudonymity-preserving.
  Both operate at the content level; account count is irrelevant (see `P2P-ADR 0012`).
- **Note:** This supersedes the defunct "NAS Ed25519 signs the manifest" origin-trust
  model (`15.2`), which lost its signer when the NAS stopped being a content origin
  (`P2P-ADR 0005` pt1).
