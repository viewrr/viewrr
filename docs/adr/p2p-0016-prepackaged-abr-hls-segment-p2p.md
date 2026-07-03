# 0016 — Pre-packaged ABR-HLS, encoded once at ingest, distributed & cached per segment

**Status:** Accepted (2026-07-03). **Supersedes `ADR-0003`** (per-device-profile / drop-ABR).
Resolves the transcode-location question the P2P re-architecture left open.

## Context

HLS-era `ADR-0003` chose on-demand, per-device-capability transcoding and dropped the ABR
ladder. In a serverless P2P mesh that has no central Hub (`p2p-0005`, `p2p-0015`: the VPS
stores zero content), "who runs the live transcoder" has no good answer — the owner
device might be a phone. Serving pre-packaged **ABR HLS** removes on-demand transcode
entirely: encode once, let each client pick the rendition its native player can decode.

## Decision

1. **Encode once, at ingest, to an ABR-HLS rendition set.** No on-demand/per-device
   transcode. Content enters the mesh already packaged as HLS (fMP4 segments +
   playlists), keyed by `contentUUID` (`p2p-0008`).
2. **Encoder = SVT-AV1** (efficient rungs) **+ x264** (one universal H.264 compat rung).
   Ingest-encode runs **only on a capable pool box (desktop/NAS)** — never on phones;
   phone-added content is queued to a pool desktop/NAS for packaging.
3. **Lean ladder:** full **AV1** rungs (1080/720/480) + **one H.264 720p compat rung**.
   Apple/legacy clients take H.264; everyone else takes AV1. Not a full dual ladder
   (that doubles pool storage). **AV1-only would break AVPlayer** (no AV1 decode on
   Apple) — the H.264 rung is required, not optional.
4. **Native HLS player per platform** (no bespoke player; honors `#130` "don't add a
   third"): macOS/iOS = **AVPlayer**, Android = **Media3/ExoPlayer**, Web/TV = **hls.js**,
   Desktop (Compose JVM) = **libmpv** (already in repo; **not** VLC/vlcj).
5. **P2P unit = HLS segment**, not Hyperdrive file block. Segment source selection reuses
   `p2p-0009` — nearest by Plus Code + uplink, **sequential fallback chain** (nearest →
   next-best on drop). No precomputed N×N mesh distance matrix (doesn't scale;
   proximity already orders peers).
6. **Client segment cache** = public content, **RF=1, LRU-evictable** (`p2p-0011`).
   Cached segments are deleted when the source title/episode is deleted. **Private
   originals keep RF≥2** (`p2p-0011`) — "no replica" applies to public content only.
7. **Prefetch:** on play, pull segments ahead + the next episode, bounded by free device
   storage (`p2p-0011` ≥20% slice). Cache-first playback so the network isn't the
   throttle.

## Consequences

- No live transcoder anywhere — the hardest part of `p2p-0003` disappears.
- Ingest is heavier (encode a ladder once) and gated to capable boxes; phone-only users
  depend on a pool desktop/NAS to package.
- Pool storage per title grows with ladder size — kept lean (§3) to bound it.
- `p2p-0009` and `p2p-0011` are unchanged in intent, now applied at segment granularity.
