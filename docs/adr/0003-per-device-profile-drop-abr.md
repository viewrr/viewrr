---
status: superseded by p2p-0016
---

# Per-device capability profile; drop the HLS ABR ladder

> **Superseded by `p2p-0016`** (2026-07-03). The P2P re-architecture reverses this:
> content is pre-packaged as an ABR-HLS rendition set once at ingest (no on-demand
> per-device transcode), so the ABR ladder is back. See `p2p-0016`.

Each playback device declares a **capability profile** (decodable codecs/containers,
plus resolution/bitrate ceiling) carried on a **per-device Stremio key**. The Hub
transcodes a title to *exactly* that profile — a single rendition — instead of
emitting a multi-rendition adaptive (ABR) ladder. We chose this because the Stremio
protocol has **no device-capability handshake**, and producing only what a device
needs avoids wasted transcode work and bandwidth.

## Considered options

- **Full HLS ABR ladder, client self-selects** — the native HLS answer. Rejected:
  transcodes every rung up front (wasted work/bandwidth, the opposite of the goal),
  and doesn't cleanly handle "device can't decode this codec."
- **User-Agent sniffing** — rejected: fragile, guesswork.

## Consequences

- The key model changes from one-per-**user** to one-per-**device**; the profile
  rides the key, giving the Hub the capability channel the protocol lacks.
- HLS cache key becomes `(mediaId, profile)`.
- **No mid-stream adaptivity** — a network dip has no lower rung to fall to
  (rebuffer instead). Acceptable on LAN; can bite off-LAN over a tunnel. This is the
  surprising part: a future reader will wonder why viewrr deliberately forgoes ABR.
