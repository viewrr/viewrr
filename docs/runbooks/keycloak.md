# Runbook — Keycloak / account registry (SUPERSEDED)

> **This runbook is obsolete.** Keycloak/OIDC was retired in `#150`; the live
> containers were torn down 2026-07-03. viewrr no longer validates OIDC tokens,
> runs no realm, and has no `OIDC_ISSUER` / `OIDC_JWKS_URL` / OAuth clients.
> The steps below (RS256 resource-server, Google brokering, realm-role admin)
> **do not apply** to the current system. Kept only so existing links resolve.

## What replaced it

**Authentication** is self-custody Ed25519 challenge-response, served by viewrr
itself — see [`../CONNECT.md`](../CONNECT.md). Admin is a config allowlist of
Ed25519 public keys (`viewrr.auth.adminPublicKeys`), not a Keycloak realm role.

**Account registry** — a central directory (Ravencloak) that allocates unique
`@handle`s and indexes `@handle → publicKey`. It is a **directory, not an auth
gate**: it never authenticates a request or gates playback, stores no PII, and
being offline never blocks a paired device. Decision + rationale:
[ADR p2p-0013](../adr/p2p-0013-account-registry-ravencloak-directory-not-gate.md).

## Ops status

- **Ravencloak (account registry): not yet redeployed.** When it lands, put it
  behind TLS on the VPS with a real DB and document its runbook here (handle
  allocation, `@handle → publicKey` sync, revocation list). The prior
  `keycloak-db` volume was intentionally retained on the VPS for this.
- `id.viewrr.stream` (old IdP host) is decommissioned — ingress removed; delete
  its dangling Cloudflare DNS record manually (viewrr.stream zone).
