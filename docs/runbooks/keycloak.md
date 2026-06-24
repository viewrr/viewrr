# Runbook — Keycloak auth (Phase 20 #112 / #114)

viewrr authenticates **humans** via Keycloak (OIDC): Google OAuth, passkeys, SSO. Playback
**devices** keep the per-device stremio-key (TVs can't OAuth). viewrr becomes an OIDC
**resource server** — it validates Keycloak-issued RS256 tokens, it does not run login UI.
Ties to your **Ravencloak** (Keycloak IdP) project. Decisions: [ADR-0005] + Phase 20 issues.

This is ops + Keycloak config (no/low viewrr code beyond #113). Deploy is yours.

## 1. Run Keycloak
```sh
docker run -d --name keycloak -p 8081:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=<set> \
  quay.io/keycloak/keycloak:latest start-dev
```
Production: put it behind TLS, use a real DB, `start` (not `start-dev`).

## 2. Realm + clients
- Create realm **`viewrr`**.
- **Clients** (both public, PKCE — no client secret; they're SPAs/apps):
  - `viewrr-web` — redirect URIs `https://<web>/*`; web origins `+`.
  - `viewrr-mobile` — redirect URI `wtf.jobin.viewrr://oidc` (AppAuth deep link).
- viewrr (the server) needs **no client** — it only validates tokens via the realm JWKS.

## 3. Google OAuth brokering (#114)
Realm → Identity providers → add **Google** → paste Google OAuth client id/secret
(from Google Cloud console; authorized redirect = Keycloak's
`/realms/viewrr/broker/google/endpoint`). Users can now "Sign in with Google".

## 4. Passkeys / WebAuthn (#114)
Realm → Authentication → enable **WebAuthn Passwordless** as a flow/required action.
Optionally make it the primary factor so passkeys replace passwords.

## 5. Admin role → claim
- Realm roles → create **`admin`**. Assign to your user.
- Client scope / mapper → map realm role `admin` into the token as a boolean claim
  **`admin`** (viewrr's `assertAdmin()` already checks `claim("admin")`).

## 6. Point viewrr at the realm (#113)
Set on the Hub (env / .env):
```
OIDC_ISSUER=http://<keycloak>:8081/realms/viewrr
OIDC_JWKS_URL=http://<keycloak>:8081/realms/viewrr/protocol/openid-connect/certs
```
When set, viewrr validates **RS256** tokens from that issuer via JWKS (see #113 — the
verifier wiring + `jwks-rsa` dep land with this cutover, since they need a live realm to
test against). When unset, viewrr keeps the **legacy HS256 `/auth/login`** path.

## 7. Cutover (#115)
1. Recreate the admin user in Keycloak (the local `jobin`/argon2 account is throwaway).
2. Migrate the web + mobile clients to the OIDC login flow.
3. Set `OIDC_ISSUER` / `OIDC_JWKS_URL`; confirm an authed call works with a Keycloak token.
4. **Then** remove `/auth/login`, `/auth/register`, `AuthService`, `PasswordHasher`, the
   `jwtSecret` config (#115).

## Verify
```sh
# get a token from Keycloak (password grant for a quick smoke test; real clients use PKCE)
curl -s -d 'grant_type=password&client_id=viewrr-web&username=<u>&password=<p>' \
  http://<keycloak>:8081/realms/viewrr/protocol/openid-connect/token | jq -r .access_token
# then call viewrr with it once OIDC_ISSUER is set:
curl -s localhost:8080/media -H "Authorization: Bearer <token>" -o /dev/null -w '%{http_code}\n'
```
