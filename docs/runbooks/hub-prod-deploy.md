# Runbook — Hub prod deploy at `api.viewrr.stream` (#118)

Scope of this file: the **manual VPS / ops steps** a human must run to bring the Ktor Hub up in
prod. The **code prerequisites** (Dockerfile, prod-ready CORS, `PUBLIC_BASE_URL` plumbing) already
landed in the repo — this runbook is the "flip the switch" checklist that remains.

For the full Dokploy service topology (Postgres/Redis/env), see [`dokploy.md`](./dokploy.md); this
file only enumerates the #118-specific manual actions and the acceptance checks.

## What the code already provides (done — no action needed)

- **`Dockerfile`** (repo root) — multi-stage: Gradle `:server:installDist` → `eclipse-temurin:21-jre`
  runtime with **`ffmpeg` + `ffprobe`** installed (HLS transcode + codec probing). Exposes `:8080`.
- **CORS is env-driven** — `viewrr.cors.allowedHosts` reads `CORS_ALLOWED_HOSTS` (comma-separated).
  Bare `host:port` = http (dev); `scheme://host` = that scheme (prod). Entries with a scheme are
  honored so `https://app.viewrr.stream` matches the browser Origin.
- **`PUBLIC_BASE_URL`** already flows to the keyed `/stream/...`, Stremio, and download URLs
  (`AppConfig.publicBaseUrl` → PlaybackRoutes/StremioRoutes/DownloadRoutes). Just set the env var.
- **Flyway migrations** run on boot against the configured Postgres (ParadeDB).

## Manual steps that remain (human / ops — NOT done by code)

1. **Provision Postgres (ParadeDB) on the VPS.** Use the `paradedb/paradedb` image (ships
   `pg_search`; the app's Flyway creates `pgcrypto`). Create db `viewrr` + a user. Persist a volume.
   See `dokploy.md` §1 for the exact Compose service.
2. **Provision Redis on the VPS.** Official `redis:7-alpine` with `--appendonly yes` + a volume
   (NOT Dokploy's one-click bitnami template — its tags are gone). See `dokploy.md` §1.
3. **Persistent volumes for media pipeline:**
   - `HLS_ROOT` (transcode cache) — a named volume, e.g. `/var/lib/viewrr/hls`.
   - `DOWNLOADS_ROOT` — a named volume.
   - **Media library strategy** — the Hub scans local files; the VPS has no media. Decide the
     ingest/storage approach (mounted volume of the library, object storage, or an upload flow) and
     configure the library roots accordingly. This is an open product decision, not a code default.
4. **Set secrets / env on the Dokploy service** (all injected at runtime, none baked into the image):
   - `VIEWRR_ENV=production` (StartupGuards is **fatal** on dev defaults / localhost CORS in prod).
   - `JWT_SECRET` = strong random value (NOT `change-me-dev-only`).
   - `DB_R2DBC_URL`, `DB_JDBC_URL`, `DB_USER`, `DB_PASSWORD` (strong) → the ParadeDB instance.
   - `REDIS_URI` → the Redis instance.
   - `CORS_ALLOWED_HOSTS=https://app.viewrr.stream` (scheme required; no localhost).
   - `PUBLIC_BASE_URL=https://api.viewrr.stream`.
   - `TMDB_API_KEY` (optional; blank disables enrichment).
   - `CLUSTER_ENROLLMENT_SECRET` = strong random value.
5. **DNS + edge:** point `api.viewrr.stream` at the VPS (Cloudflare tunnel or A record) and terminate
   TLS so `:8080` is reachable as `https://api.viewrr.stream`. `app.viewrr.stream` (web client) is
   built with `VITE_API_BASE=https://api.viewrr.stream`.
6. **Deploy** — build/push the image (`docker build` this Dockerfile, or the existing Jib/CI path)
   and redeploy the Dokploy service.

## Acceptance checks (run after deploy)

- `https://api.viewrr.stream/health` → 200 from the VPS.
- `POST /auth/...` login works; a bearer-protected endpoint returns data.
- CORS preflight (`OPTIONS`) from `https://app.viewrr.stream` succeeds (response carries
  `Access-Control-Allow-Origin: https://app.viewrr.stream`).
- An HLS master playlist's `/stream/...` URLs are publicly resolvable (i.e. built from
  `PUBLIC_BASE_URL`, not localhost).
