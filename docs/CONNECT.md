# Connecting a frontend to viewrr

How the web app, the mobile app, and playback devices authenticate to the viewrr
backend — and what the backend can and cannot restrict.

- **API base URL:** `https://api.viewrr.stream`
- **OpenAPI / try-it:** `https://docs.viewrr.stream`

> **Auth model changed (#150 / ADR p2p-0013).** Keycloak/OIDC is retired.
> Authentication is **self-custody Ed25519 challenge-response**, handled by the
> viewrr backend itself — there is no login server on the auth path. A separate
> **Account registry (Ravencloak)** allocates human-facing `@handle`s but is a
> directory, not a gate (see *Account registry* below). The old
> `id.viewrr.stream` IdP no longer exists.

## TL;DR

1. The client holds an **Ed25519 key pair** (the self-custody identity; one key
   per human, copied to each device at pairing — the wallet model).
2. **Register** the public key once, then **prove ownership** by signing a
   server-issued challenge. The backend returns a normal **session JWT**.
3. Send **`Bearer <access_token>`** on every API call. The token is an HS256
   session token minted by viewrr — not by any external IdP.

## Authentication flow (web + mobile)

Same three calls for every client; keys and signatures are **lowercase hex**.

```
# 1. Register the public key (idempotent — re-registering returns 200, never dups)
POST https://api.viewrr.stream/identity/register
{ "publicKey": "<hex>", "signature": "<Ed25519(REGISTER_MESSAGE) hex>", "displayName": "optional petname" }
→ 200 { "accountId": "...", "publicKey": "<hex>", "displayName": null }

# 2. Get a single-use challenge nonce
GET https://api.viewrr.stream/identity/challenge
→ 200 { "challenge": "<nonce>" }

# 3. Prove ownership → receive session tokens
POST https://api.viewrr.stream/identity/verify
{ "publicKey": "<hex>", "challenge": "<nonce>", "signature": "<Ed25519(challenge) hex>" }
→ 200 { access + refresh token pair }
```

Then call the API:

```
GET https://api.viewrr.stream/media
Authorization: Bearer <access_token>
```

- **Web (`viewrr-web`):** keypair generated/stored client-side; a passkey or
  biometric unlocks the locally-encrypted `secretKey` (local unlock, not a
  per-device WebAuthn identity — see ADR p2p-0013).
- **Mobile (`viewrr-mobile`):** keypair derived from a 12-word BIP39 mnemonic
  (`#142`); `secretKey` encrypted at rest, unlocked by a local master
  password / biometric. No deep-link OAuth flow.

## Playback devices (TVs / Stremio)

TVs and Stremio addons embed a credential in a URL and can't do per-request
challenge-response, so they use a **per-device key** (the Stremio key): a
long-lived, revocable capability token bound to the **Identity**, optionally
carrying that device's capability profile (codecs / max resolution). The Hub
transcodes to match the profile instead of emitting a full ABR ladder. This is
a separate path from the interactive login above.

## Account registry (Ravencloak)

A central, eventual-consistent directory that allocates unique **`@handle`s**
and indexes `@handle → publicKey`. It stores **no PII**. It is a **directory,
not a gate** (ADR p2p-0013): authoritative only for handle uniqueness and
login/SSO UX, and **never required to authenticate or to play** on an
already-paired device. If Ravencloak is offline, registration/handle-claim
pauses but auth and playback continue. *(Redeploy pending.)*

## What the backend can restrict (and the honest limits)

Authentication is self-custody and **registration is open** — anyone can
generate a key pair and register it. There is no "approved frontend" gate:
Keycloak's OAuth client allowlist and `azp` check are **gone** (`#150`). Be
clear-eyed about what remains:

| Layer | Gate | Status |
|---|---|---|
| **1. Ed25519 key proof** | Every API call carries a session token minted only after a signed challenge. Proves control of a key — not which binary called. | ✅ active |
| **2. Mobile app attestation** (Play Integrity / Apple App Attest) | Server-verified proof the call came from the genuine, unmodified app. The only true "our app only" guarantee — mobile-only. | ⛔ not yet |
| **3. Abuse controls** | Cloudflare bot management + rate limiting in front of everything. | ✅ active |

> **Limitation:** a key proof identifies *an* Identity, not *our official build*.
> Because clients are FOSS and self-custody, anyone can build a client and
> register a key. For a strong "our app only" guarantee on mobile, add layer 2;
> on the web there is no equivalent (browser code is always inspectable). This is
> the industry ceiling, not a viewrr limitation.

## Admin endpoints

Routes under `/admin/*` additionally require the session token to carry
`admin: true`. That claim is set at verify time from a config allowlist of
Ed25519 public keys (`viewrr.auth.adminPublicKeys`, lowercase hex) — **admin is
"you hold a key on the allowlist"**, there is no runtime promotion endpoint, and
an empty allowlist means no admins. Non-admins get `404` (the admin surface is
not leaked).
