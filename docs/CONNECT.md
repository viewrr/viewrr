# Connecting a frontend to viewrr

How the web app, the mobile app, and playback devices authenticate to the viewrr
backend — and how the backend restricts which frontends may connect.

- **API base URL:** `https://api.viewrr.stream`
- **Identity provider (Keycloak):** `https://id.viewrr.stream/realms/viewrr`
- **OpenAPI / try-it:** `https://docs.viewrr.stream`

## TL;DR

1. Humans log in through **Keycloak** (OIDC, Authorization Code + PKCE). Google and
   passkeys are brokered by Keycloak — the frontend never sees a password.
2. The frontend sends the resulting **`Bearer <access_token>`** on every API call.
3. The backend validates the token's **signature, issuer, audience, and `azp`** (the
   client it was minted for). Only **approved client IDs** are accepted.

## Web client (`viewrr-web`)

- **client_id:** `viewrr-web` (public client, PKCE `S256`, no secret)
- **redirect URIs:** `https://app.viewrr.stream/*`, `http://localhost:5173/*` (dev)
- **flow:** Authorization Code + PKCE. Use a standard OIDC library (e.g. `oidc-client-ts`).

OIDC endpoints (discovery: `…/realms/viewrr/.well-known/openid-configuration`):

```
authorize: https://id.viewrr.stream/realms/viewrr/protocol/openid-connect/auth
token:     https://id.viewrr.stream/realms/viewrr/protocol/openid-connect/token
logout:    https://id.viewrr.stream/realms/viewrr/protocol/openid-connect/logout
jwks:      https://id.viewrr.stream/realms/viewrr/protocol/openid-connect/certs
```

Call the API:

```
GET https://api.viewrr.stream/media
Authorization: Bearer <access_token>
```

## Mobile client (`viewrr-mobile`)

- **client_id:** `viewrr-mobile` (public client, PKCE `S256`)
- **redirect URI:** `wtf.jobin.viewrr://oidc` (AppAuth deep link)
- **flow:** Authorization Code + PKCE via AppAuth (Android/iOS). Same token + API usage as web.

## Playback devices (TVs / Stremio)

TVs can't run an OAuth browser flow, so playback devices use a **per-device key** issued
from an authenticated session, carrying that device's capability profile (codecs / max
resolution). The Hub transcodes to match the device instead of emitting a full ABR ladder.
This is a separate path from the human OIDC login above.

## How the backend restricts frontends (and the honest limits)

viewrr layers the industry-standard controls. Be clear-eyed about what each one can and
cannot do — a public client (SPA or mobile app) holds **no secret**, and viewrr is FOSS,
so the `client_id` is not confidential. You cannot cryptographically prove "this request
came from our official binary" on the web. What you *can* do is gate registered clients,
attest mobile binaries, and watch for abuse.

| Layer | Gate | Status |
|---|---|---|
| **1. OAuth client allowlist** (Keycloak) | Only **registered** `client_id`s with registered redirect URIs can complete login. **Registering a client in Keycloak = an admin approving a frontend.** | ✅ active |
| **2. `azp` allowlist** (`AUTH_ALLOWED_CLIENTS`) | The Hub rejects any token whose `azp` (authorizing client) is not approved — even a valid user token minted for another client is refused. | ✅ active |
| **3. Mobile app attestation** (Play Integrity / Apple App Attest) | Server-verified proof the call came from the genuine, unmodified app on a genuine device. The only true "our app only" guarantee — mobile-only. | ⛔ not yet |
| **4. Abuse controls** | Cloudflare bot management + rate limiting in front of everything. | ✅ active |

**Approving a new frontend (admin):**

1. In Keycloak (`https://id.viewrr.stream`, realm `viewrr`) create a client:
   public, PKCE `S256`, with the frontend's exact redirect URI(s).
2. Add its `client_id` to the Hub env `AUTH_ALLOWED_CLIENTS` (comma-separated) and redeploy.
   Example: `AUTH_ALLOWED_CLIENTS=viewrr-web,viewrr-mobile`.
3. Until both are done, that frontend's tokens are rejected at the API.

> **Limitation:** layers 1–2 ensure a caller used *our registered client and login flow*;
> they do **not** prove the calling code is our official build (anyone can build a client
> with the public `client_id`). For a strong "our app only" guarantee on mobile, add layer 3.
> On the web there is no equivalent — browser code is always inspectable. This is the
> industry ceiling, not a viewrr limitation.

## Admin endpoints

Routes under `/admin/*` additionally require the token to carry `admin: true` (mapped in
Keycloak from the user's `admin` attribute / realm role). Non-admins get `404` (the admin
surface is not leaked).
