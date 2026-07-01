# 0012 — No device/browser fingerprinting; catalogue integrity is data-layer, not identity-layer

**Status:** Accepted (2026-07-01)

## Context

Chrome/device fingerprinting was proposed to prevent spam accounts. The stated concern
was spam accounts **poisoning the catalogue**.

## Decision

**Do not build device or browser fingerprinting.** It is rejected because:

- It contradicts the identity model — keypairs are permissionless and offline-
  generatable (`P2P-ADR 0001`); account count cannot be gated without breaking self-custody.
- It only touches the Vue **web** client (native apps have no browser fingerprint) and
  is trivially bypassed.
- It is a privacy regression (canvas/font/UA tracking) contradicting `P2P-ADR 0006`/`P2P-ADR 0010`, and
  is weak/evadable with high false-positive collateral.
- It targets the wrong layer: account count does not poison the catalogue.

**Catalogue integrity is enforced at the data layer instead:**
- **TMDB allowlist + server-fetched metadata** (`P2P-ADR 0008`): fake titles are rejected;
  metadata comes from TMDB, not the client, so it cannot be stuffed. Accounts have no
  arbitrary catalog write.
- **Content-level checks** (`P2P-ADR 0009`): client-side TMDB sanity-check on pull + anonymous
  flagging that de-prioritizes bad drives — handle the only residual (mislabeled bytes
  under a valid UUID), which a single account can do as well as a thousand.

## Consequences

- **Good:** Catalogue is spam-resistant without tracking, PII, or capping identities.
  Preserves the permissionless, no-PII, no-backdoor posture end to end.
- **Note:** If a scarce *resource* (e.g. free backup tier, `P2P-ADR 0011`) ever needs Sybil
  resistance, defend the resource with per-pubkey quota / pool-contribution ratio /
  proof-of-work — never identity fingerprinting.
