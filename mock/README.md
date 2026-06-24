# viewrr API mock

A standalone mock of the viewrr client API, driven entirely by the OpenAPI spec at
[`../docs/api/openapi.yaml`](../docs/api/openapi.yaml). The web (Vue) and mobile (Compose)
clients can build against this without the live Kotlin backend.

## Run it (Prism)

[Prism](https://github.com/stoplightio/prism) serves the example responses baked into the
spec — no custom server, no database.

```bash
# from the repo root
npx @stoplight/prism-cli mock docs/api/openapi.yaml -p 4010
```

Mock base URL: **http://localhost:4010** (already the first `servers` entry in the spec).
Point each client's API base there:

- viewrr-web:    `VITE_API_BASE=http://localhost:4010`
- viewrr-mobile: `BASE_URL=http://localhost:4010`

### Static vs dynamic responses

- **Static (default):** returns the exact `examples:` from the spec. Deterministic — best
  for snapshot tests and predictable UI states. Every endpoint has a realistic example, so
  static mode is the recommended default.
- **Dynamic (`-d`):** Prism generates random data that matches each schema. Useful for
  exercising list rendering / edge cases.

```bash
npx @stoplight/prism-cli mock docs/api/openapi.yaml -p 4010 -d
```

## What's mocked

Every client-facing endpoint in the spec returns a useful payload from its example:

| Area     | Endpoints |
|----------|-----------|
| Auth     | `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `POST /me/stremio-key` |
| Browse   | `GET /media`, `/media/search`, `/series` |
| Detail   | `GET /media/{id}`, `/series/{showTitle}` |
| Home     | `GET /home/top`, `/home/featured`, `/me/continue-watching`, `/me/recommendations` |
| Watch    | `POST /watch-events`, `GET /watch-events/me?mediaId=` |
| Playback | `GET /playback/{id}`, `GET /stream/k/{key}/{mediaId}/playlist.m3u8` |
| Music    | `GET /music/albums`, `/music/albums/{album}/tracks`, `/music/tracks/{id}/audio` |

## Notes for client devs

- **Auth is not enforced by the mock.** Prism does not validate the bearer token; send any
  `Authorization: Bearer <anything>` and you'll get the example back. The real server
  requires a valid JWT from `POST /auth/login`.
- **`GET /watch-events/me` requires `?mediaId=<uuid>`** — it is per-media, not a global feed.
- **Playback flow:** call `GET /playback/{id}`, take the returned `url` (a keyed
  `/stream/k/{key}/{mediaId}/playlist.m3u8`), and hand it straight to the HLS player. That
  URL is authenticated by the key in the path, NOT by a bearer header. The mock's
  `/stream/...` endpoint returns a sample `.m3u8` body but cannot serve real video — for
  actual playback you need the live backend or a recorded HLS fixture.
- **`/music/tracks/{id}/audio`** returns binary in production; the mock returns an empty/
  placeholder body. Don't expect playable audio from the mock.
- **Picking a single example:** when a response declares multiple examples, request a
  specific one with the `Prefer` header, e.g. `Prefer: example=<name>`.

## Choosing the tool

Prism alone covers everything here because the spec carries rich examples for every
response. There is intentionally **no custom mock server** — keep the spec as the single
source of truth. If a future endpoint needs behavior Prism can't express (stateful flows,
real binary streams), add a small static fixture under `mock/` and document it here rather
than forking the contract.
