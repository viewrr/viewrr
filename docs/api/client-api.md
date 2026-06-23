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
- Auth: `Authorization: Bearer <token>` (today legacy HS256 JWT from `/auth/login`;
  **migrating to Keycloak OIDC RS256** — #112/#113. Treat the bearer as opaque; validate
  via the issuer once Keycloak lands).
- Playback to devices is **unauthenticated by JWT** — it uses a **per-device stremio-key**
  in the URL path (TVs can't carry a bearer). See Playback.

## Auth  (→ Keycloak, Phase 20)
| Method | Path | Status | Notes |
|---|---|---|---|
| POST | `/auth/login` | ✅ (legacy) | HS256 JWT; **retiring** for Keycloak (#115) |
| POST | `/auth/refresh` | ✅ (legacy) | |
| POST | `/auth/logout` | ✅ | |
| — | Keycloak OIDC (Google/passkey/SSO) | 🔜 | #112-114; web+mobile are OIDC clients |
| POST | `/me/stremio-key` | ✅ | mint/return the caller's long-lived per-device key |

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
| GET | `/media/{id}` | 🔜 | single-item detail (today detail comes via Stremio `meta`; add a clean REST detail) |
| GET | `/series/{showTitle}` | ✅ | show + seasons/episodes |

## Search
| GET | `/media/search?q=` | ✅ | see `media/MediaSearchRoutes.kt` (pg_search/BM25) |

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
`/home/top`, `/home/featured`, `GET /media/{id}` detail, `GET /playback/{mediaId}` resolve,
confirm `/media` sort params, then Keycloak (#112-115). Capability-profile + locality on
playback resolve come with Phase 15.
