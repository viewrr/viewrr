# Runbook — Deploy viewrr to Dokploy

Tag-driven: `git tag v0.1.0 && git push origin v0.1.0` → CI (`.github/workflows/release.yml`)
builds the image to GHCR, cuts a GitHub Release, and calls the Dokploy API to redeploy.
Do the one-time setup below first.

## 1. Databases — create BOTH in the same Dokploy project

**Postgres — must have `pg_search` + `pgcrypto`.** Dokploy's one-click Postgres is stock and
does NOT have `pg_search` (used by `/media/search`). So add Postgres as a **Compose / custom
Docker service** using a ParadeDB image instead of the one-click DB:

```yaml
# Dokploy -> Compose service (or Docker), image with pg_search + pgcrypto
services:
  db:
    image: paradedb/paradedb:latest
    environment:
      POSTGRES_DB: viewrr
      POSTGRES_USER: viewrr
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - viewrr-pg:/var/lib/postgresql/data
volumes:
  viewrr-pg:
```
`pgcrypto` is created by the app's Flyway migration; ParadeDB ships `pg_search`. (Stock postgres
works ONLY if you don't need full-text search — not recommended.)

**Redis — use the OFFICIAL image, not Dokploy's one-click.** The one-click template pulls a
`bitnami/redis` tag that no longer exists (Bitnami pulled most Docker Hub tags in 2025:
`docker.io/bitnami/redis:8.2.0: not found`). Add Redis as a Compose/Docker service:

```yaml
services:
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - viewrr-redis:/data
volumes:
  viewrr-redis:
```

Both live in the same project → the app reaches them by their internal service hostname.

## 2. Application

- New **Application**, source = Docker image **`ghcr.io/viewrr/viewrr:latest`** (add a GHCR
  registry credential if the package is private).
- The image already bundles **ffmpeg/ffprobe** (Jib layers static binaries) — no extra step.
- Expose **port 8080**, attach a **domain** (Dokploy/Traefik gives TLS).

## 3. Persistent volumes (or you lose data on redeploy)

- **`HLS_ROOT`** → a volume (e.g. `/data/hls`). Without it the transcode cache is wiped each deploy.
- **Media library** → mount the dir(s) holding your media into the Hub, and set
  `AGENT_LIBRARY_ROOTS` / library roots to those paths. (Or run the Hub purely as transcoder and
  keep media on separate Node agents once Headscale is up.)

## 4. Environment variables (Application → Environment)

```
VIEWRR_ROLE=hub
PUBLIC_BASE_URL=https://viewrr.yourdomain        # stream URLs embed this — must be the real domain
DB_R2DBC_URL=r2dbc:postgresql://<pg-host>:5432/viewrr
DB_JDBC_URL=jdbc:postgresql://<pg-host>:5432/viewrr
DB_USER=viewrr
DB_PASSWORD=<secret>
REDIS_URI=redis://<redis-host>:6379/0
JWT_SECRET=<secret>                              # until Keycloak (#113) replaces it
CLUSTER_ENROLLMENT_SECRET=<secret>
TMDB_API_KEY=<your key>
HLS_ROOT=/data/hls
```
`<pg-host>` / `<redis-host>` = the internal hostnames Dokploy shows for those services.

## 5. Release-action secrets (repo → Settings → Secrets → Actions)

```
DOKPLOY_URL       https://dokploy.yourhost       # no trailing slash
DOKPLOY_API_KEY   <Dokploy profile -> API token>
DOKPLOY_APP_ID    <the viewrr application id>
```
(No-API-key alternative: give the app a Deploy Webhook in Dokploy and use `DOKPLOY_DEPLOY_WEBHOOK`
— see the commented line in `release.yml`.)

## 6. Deploy

```
git tag v0.1.0 && git push origin v0.1.0
```
CI builds → GHCR → GitHub Release (changelog) → Dokploy redeploys. First boot runs Flyway
migrations against the Dokploy Postgres automatically.

## API docs (Scalar) at docs.viewrr.stream

Self-hosted, static (no SaaS). `docs/scalar/` renders `docs/api/openapi.yaml` via Scalar.

- Dokploy → new **Application**, source = this repo, **Dockerfile path `docs/scalar/Dockerfile`**,
  build context **`/`** (root — the image copies the spec from `docs/api/`).
- Domain: **docs.viewrr.stream** (Traefik TLS). Serves on :80.
**Auto-deploy on main push:** use Dokploy's **GitHub provider** for the docs app (repo
`viewrr/viewrr`, branch `main`, Dockerfile `docs/scalar/Dockerfile`, build context `/`) and
toggle **Auto Deploy ON**. Dokploy installs the webhook and rebuilds docs on every push to main.
No GitHub Action needed.

## Notes
- Single-Hub deploy works today. Multi-Node (media on a NAS, Hub pulls over the mesh) needs the
  **Headscale** deploy (#70) + Nodes running `VIEWRR_ROLE=agent`.
- Auth is the legacy JWT until **Keycloak** (#112–115) is wired.
