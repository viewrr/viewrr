# 0010 — Operator power is de-index only; no backdoor; public catalog is TMDB-allowlisted

**Status:** Accepted (2026-07-01)

## Context

viewrr hosts the central catalog index (`P2P-ADR 0008`) and will receive takedown notices.
The operator's stance: maximally decentralized, no ability to delete content, no
backdoor or key escrow, not legally the custodian of files.

## Decision

1. **The operator's only power is de-indexing** — removing a catalog row. This makes a
   title unsearchable in the central catalog. It does **not** delete the file; the bytes
   remain reachable via direct public link / `contentUUID` in the mesh.
2. **No backdoor.** No admin decrypt, no key escrow, no operator-held secrets. Content
   is encrypted to the user; the server cannot read private content. (Reaffirms the
   self-custody invariant across `P2P-ADR 0001`/`P2P-ADR 0007`.)
3. **Public catalog is TMDB-allowlisted.** A row may be public only if it matches a
   TMDB title. Anything non-TMDB is **private-by-default** and never publicly indexed.
4. **De-indexed / non-TMDB `contentUUID`s go on a blocklist** so mesh auto-contribution
   cannot (re)insert them into the public catalog.

## Consequences

- **Good:** Clear, honest operator surface — one lever (de-index), no secret powers.
- **CSAM / illegal content — architectural limit (must be understood):** the operator
  **cannot detect or remove privately hosted content.** Classification would require
  scanning plaintext (a backdoor, forbidden) or perceptual hashing (also needs
  plaintext). The TMDB allowlist is *not* CSAM detection — it is an allowlist that keeps
  non-TMDB content (including CSAM) out of the **public** catalog. Private encrypted
  hosting is invisible to viewrr by design, exactly as with Tor / BitTorrent / Signal.
- **Legal:** "not legally liable" is an aspiration, not a guarantee; some jurisdictions
  impose duties even on blind intermediaries. Operating entity + jurisdiction is a legal
  decision requiring counsel (operator is India-based; 2021 IT Rules + traceability
  mandates differ from US DMCA §512). Out of architecture scope.
- **Repeat-infringer policy is content-level** (blocklisted UUIDs), not user-level,
  because availability is pseudonymous (`P2P-ADR 0008`). This is a weaker safe-harbor posture;
  accepted as the cost of no-backdoor pseudonymity.
