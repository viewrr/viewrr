# viewrr Client API — v0 contract

The API viewrr-web and viewrr-mobile build against. **Not the Jellyfin API.** This is the
v0 working contract (markdown); a formal OpenAPI spec is [#108](https://github.com/viewrr/viewrr/issues/108).
Status legend: ✅ exists today · 🔜 to build (tracked in Phase 20).

> Frontend agents: build a typed API layer against this. Where a row needs an endpoint
> marked 🔜, stub it client-side and the backend will land it. File issues against
> `viewrr/viewrr` for gaps, don't invent silently.

## Base + conventions
- Base URL: the Hub, `${PUBLIC_BASE_URL}` (dev `http://localhost:8080`).
- JSON everywhere; UUIDs as strings; timestamps ISO-8601.
- Auth: `Authorization: Bearer <token>` — an HS256 session token minted by the Hub
  after a self-custody Ed25519 challenge→verify (#150; Keycloak/OIDC retired). Treat
  the bearer as opaque. See [../CONNECT.md](../CONNECT.md).
- Playback to devices is **unauthenticated by JWT** — it uses a **per-device stremio-key**
  in the URL path (TVs can't carry a bearer). See Playback.

## Auth  (self-custody Ed25519 — #150 / ADR p2p-0013)
| Method | Path | Status | Notes |
|---|---|---|---|
| POST | `/identity/register` | ✅ | register a public key (idempotent); `{publicKey, signature, displayName?}` |
| GET | `/identity/challenge` | ✅ | single-use nonce to sign |
| POST | `/identity/verify` | ✅ | sign the challenge → HS256 session token pair |
| — | Account registry: Ravencloak `@handle → publicKey` (directory, not gate) | 🔜 | ADR p2p-0013; redeploy pending |
| POST | `/me/stremio-key` | ✅ | mint/return the caller's long-lived per-device key |

### Ceremony details + gotchas (the parts we kept reverse-engineering)
Source of truth: `identity/IdentityRoutes.kt`, `identity/IdentityModels.kt`, `auth/TokenService.kt`.
- **Encoding:** `publicKey`/`signature` are **lowercase hex** — raw 32-byte pubkey (64 chars),
  raw 64-byte sig (128 chars). No base64, no `0x`.
- **Register signs a fixed literal:** Ed25519 over the UTF-8 bytes of `viewrr:register`. Idempotent
  (201 first, 200 after). Returns `AccountView {accountId, publicKey, displayName?}` — **not tokens.**
- **Verify signs the challenge string:** Ed25519 over the UTF-8 bytes of the `challenge` value.
  Nonce is single-use — fetch a fresh `/identity/challenge` per verify attempt.
- **Access token:** HS256 JWT, **TTL 15 min** (`viewrr.auth.accessTtlMinutes`), `sub` = account UUID.
- **No refresh endpoint exists today.** A 30-day `refreshToken` is returned but there is **no route
  to redeem it** — on 401, **re-run challenge→verify**. Don't hardcode a re-auth interval (the old
  14-min guess); drive it off the 15-min TTL.
- **401 is opaque:** bad signature / unknown-or-consumed challenge / unregistered key all return
  the same `401 {error}`. No oracle to distinguish them.
- **Error shapes:** handled errors (validation 400, identity 401, rec-engine 503) carry
  `{ "error": "…" }`. A **bare 404** (missing OR parental-hidden media) and a **JWT auth failure**
  (missing/expired/invalid bearer) return the status with an **empty body**.

## Browse / Home
The Apple-TV home is rows. Compose from these:
| Row | Source | Status |
|---|---|---|
| Continue Watching | `GET /me/continue-watching` | ✅ |
| Recommended / For You | `GET /me/recommendations` | ✅ |
| Recently Added | `GET /media?sort=createdAt&order=desc` | ✅ list exists; confirm sort params |
| Top 10 (popular) | `GET /home/top` | 🔜 popularity ranking endpoint |
| Featured (curated) | `GET /home/featured` | 🔜 curated/editorial picks |
| Shows | `GET /series` | ✅ |
| Music albums | `GET /music/albums` | ✅ |

🔜 **Optional aggregate:** `GET /home` returning all rows in one call (fewer round-trips
for TV). Decide vs per-row fetch — lean per-row for now, add aggregate if latency bites.

Media item shape (current — see `media/MediaListRoutes.kt`): `id, title, cleanTitle,
showTitle, season/episode, year, poster, backdrop, overview, durationSecs, contentRating`.
(poster/backdrop/overview from TMDb enrichment, may be null.)

## Detail
| Method | Path | Status | Notes |
|---|---|---|---|
| GET | `/media/{id}` | ✅ | single-item detail — full `MediaListItem` (404 when parental-hidden) |
| GET | `/series/{showTitle}` | ✅ | show + seasons/episodes |

## Search
| GET | `/media/search?q=&limit=` | ✅ | pg_search/BM25, `q` = Tantivy syntax; see `media/MediaSearchRoutes.kt` |

⚠️ **Search returns a REDUCED shape** (`MediaSearchHit`: `id, title, hlsPath, durationSecs,
mimeType, contentRating`) — it OMITS `cleanTitle`, `year`, `showTitle`, and artwork that `/media`
returns. `title` embeds the year (`"Sintel (2010)"`); `cleanTitle`+`year` are separated and live
**only on `/media`**. Match search hits back by `id` if you need those fields.

## Watch progress (drives Continue Watching + resume)
| POST | `/watch-events` | ✅ | report progress `{mediaId, positionSecs, eventType, sessionId}` |
| GET | `/watch-events/me` | ✅ | caller's events |
| GET | `/me/continue-watching` | ✅ | resume list |

## Playback  (the device flow)
Devices cannot send a bearer, so playback authorizes via the **stremio-key path prefix**:
1. Authenticated client calls `POST /me/stremio-key` → `{ key }` (long-lived).
2. Resolve a title to HLS: `GET /stream/k/{key}/{mediaId}/playlist.m3u8` ✅ (segments are
   relative under the same `/k/{key}/{mediaId}/` prefix — keep the prefix, don't use query auth).
3. 🔜 **`GET /playback/{mediaId}`** (authed) — a clean resolve returning
   `{ url, type:"hls", drm:null, subtitles:[…], startPositionSecs }` so clients don't
   hand-assemble URLs. Honors capability profile + locality (Phase 15). Build this; it's the
   one playback endpoint clients should call.
- Subtitles: `GET /media/{mediaId}/subtitles` ✅ · Trickplay: `GET /media/{mediaId}/trickplay` ✅

## Stremio addon (separate surface — do not build clients on this)
`/stremio/{key}/{manifest.json,catalog,meta,stream,subtitles}` exists for **third-party
Stremio clients** (Nuvio). First-party viewrr clients use the REST above, not the addon.

## Backend gaps to close (Phase 20, this agent)
`/home/top`, `/home/featured`, `GET /playback/{mediaId}` resolve. (`GET /media/{id}` detail and
`/media` sort params now shipped.) Auth is done (self-custody Ed25519, #150); the Account
registry (Ravencloak, ADR p2p-0013) is the remaining identity work. Capability-profile +
locality on playback resolve come with Phase 15.
